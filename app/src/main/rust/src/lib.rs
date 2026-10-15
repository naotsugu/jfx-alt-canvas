use std::borrow::Cow;
use std::collections::HashMap;
use std::f64::consts::PI;
use std::slice;
use std::ffi::{c_char, CStr};
use std::sync::{Arc, LazyLock, Mutex, MutexGuard};
use vello::{kurbo::{Affine, Arc as KArc, BezPath, Join, Cap, Rect, Stroke, Ellipse, RoundedRect,
                    Line, PathEl, Point as KPoint, Shape},
            peniko::{BlendMode, Brush, Color, Compose, Extend, Fill, Gradient, Mix, StyleRef,
                     ImageAlphaType, ImageBrush, ImageData, ImageFormat},
            Scene, Glyph};
use vello::wgpu;
use parley::{Alignment, AlignmentOptions, FontContext, FontFamily, FontWeight, Layout,
             LayoutContext, PositionedLayoutItem, StyleProperty};
use vello::peniko::Blob;

// -- shared resources --------------------------------------------------------

/// A point returned by value to the caller (two f64, same layout as a C struct).
#[repr(C)]
pub struct Point { x: f64, y: f64, }

#[repr(C)]
#[derive(Default)]
pub struct Metrics {
    ascent: f64, descent: f64, leading: f64, line_height: f64, baseline: f64, advance: f64,
}

/// Text resources shared by all RenderContexts.
/// ranged_builder() needs both contexts as &mut, so they live under one lock.
struct TextResource {
    font_cx: FontContext,
    layout_cx: LayoutContext<()>,
}

/// Heavy resources shared by all RenderContexts.
/// Device and Queue are cheap to clone and thread-safe, so each RenderContext keeps
/// its own clone. Renderer and TextEngine need &mut access, so they are behind Mutexes.
struct SharedResource {
    device: wgpu::Device,
    queue: wgpu::Queue,
    renderer: Mutex<vello::Renderer>,
    text: Mutex<TextResource>,
}
impl SharedResource {
    fn new() -> SharedResource {

        // initialize wgpu device and queue for GPU rendering
        let instance = wgpu::Instance::default();
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::default(),
            force_fallback_adapter: false,
            compatible_surface: None,
            ..Default::default()
        }))
        .expect("failed to find an appropriate adapter");
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("Device"),
            required_features: wgpu::Features::empty(),
            ..Default::default()
        }))
        .expect("failed to create device");

        // initialize renderer
        let renderer = vello::Renderer::new(
            &device,
            vello::RendererOptions {
                use_cpu: false,
                antialiasing_support: vello::AaSupport::area_only(),
                num_init_threads: None,
                pipeline_cache: None,
            },
        ).expect("failed to create renderer");

        SharedResource {
            device,
            queue,
            renderer: Mutex::new(renderer),
            text: Mutex::new(TextResource {
                font_cx: FontContext::new(),
                layout_cx: LayoutContext::new(),
            }),
        }

    }
}

/// Global slot for the shared resources. It is filled lazily by the first
/// create_render_context() and emptied by release_shared_resources().
static SHARED: Mutex<Option<Arc<SharedResource>>> = Mutex::new(None);

/// Decoded images, shared by all RenderContexts and addressed by a numeric handle.
struct ImageStore {
    next: u64,
    map: HashMap<u64, ImageBrush>,
}
static IMAGES: LazyLock<Mutex<ImageStore>> =
    LazyLock::new(|| Mutex::new(ImageStore { next: 0, map: HashMap::new() }));

/// Locks a mutex and ignores poisoning, so a panic in one call
/// does not break every later call across the FFI boundary.
fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// Returns the shared resources, creating them on first use.
fn acquire_shared() -> Arc<SharedResource> {
    let mut slot = lock(&SHARED);
    if let Some(shared) = slot.as_ref() {
        return Arc::clone(shared);
    }
    let shared = Arc::new(SharedResource::new());
    *slot = Some(Arc::clone(&shared));
    shared
}

/// Drops the global reference to the shared resources (GPU device, renderer, font caches).
/// Existing RenderContexts keep working: they hold their own reference, and the resources
/// are actually freed when the last of them is destroyed.
/// A RenderContext created after this call gets a fresh set of shared resources.
/// Call this once at shutdown, after destroying all RenderContexts, to free everything.
#[unsafe(no_mangle)]
pub extern "C" fn release_shared_resources() {
    let released = lock(&SHARED).take();
    if released.is_some() {
        println!("Shared resources released.");
    }
}

// -- context -----------------------------------------------------------------

/// One active clip. The path is already in device space.
#[derive(Clone)]
struct Clip {
    id: u64,
    rule: Fill,
    path: Arc<BezPath>,
}

/// The attributes that save_state() / restore_state() push and pop
/// (same set as JavaFX GraphicsContext.save()). The current path is not part of it.
#[derive(Clone)]
struct State {
    transform: Affine,
    global_alpha: f32,
    blend: BlendMode,
    fill_rule: Fill,
    fill_brush: Brush,
    stroke_brush: Brush,
    line_width: f64,
    line_cap: Cap,
    line_join: Join,
    miter_limit: f64,
    dashes: Vec<f64>,
    dash_offset: f64,
    font_family: String,
    font_size: f32,
    font_weight: f32,
    /// 0 = left, 1 = center, 2 = right
    text_align: u32,
    /// 0 = baseline, 1 = top, 2 = center, 3 = bottom
    text_baseline: u32,
    clips: Vec<Clip>,
}
impl State {
    fn new() -> State {
        State {
            transform: Affine::IDENTITY,
            global_alpha: 1.0,
            blend: BlendMode::default(),
            fill_rule: Fill::NonZero,
            fill_brush: Brush::Solid(Color::BLACK),
            stroke_brush: Brush::Solid(Color::BLACK),
            line_width: 1.,
            line_cap: Cap::Square,
            line_join: Join::Miter,
            miter_limit: 10., // same default as JavaFX
            dashes: Vec::new(),
            dash_offset: 0.,
            font_family: "sans-serif".to_string(),
            font_size: 14.,
            font_weight: 500., // 400.
            text_align: 0,
            text_baseline: 0,
            clips: Vec::new(),
        }
    }
}

/// RenderContext
pub struct RenderContext {
    device: wgpu::Device,
    queue: wgpu::Queue,
    texture: wgpu::Texture,
    readback_buffer: wgpu::Buffer,
    width: u32,
    height: u32,
    scene: Scene,
    st: State,
    stack: Vec<State>,
    /// the current path, in user space
    path: BezPath,
    /// ids of the clip layers that are currently open in the scene
    open_clips: Vec<u64>,
    next_clip_id: u64,
    // declared last so it is dropped after the GPU resources above
    shared: Arc<SharedResource>,
}
impl RenderContext {
    /// Builds the Stroke from the current line width, cap, join, miter limit and dashes.
    fn stroke_style(&self) -> Stroke {
        let st = &self.st;
        let stroke = Stroke::new(st.line_width)
            .with_caps(st.line_cap)
            .with_join(st.line_join)
            .with_miter_limit(st.miter_limit);
        if st.dashes.is_empty() {
            stroke
        } else {
            stroke.with_dashes(st.dash_offset, st.dashes.iter().copied())
        }
    }

    /// The current fill / stroke paint with the global alpha applied.
    fn brush(&self, stroke: bool) -> Brush {
        let brush = if stroke { &self.st.stroke_brush } else { &self.st.fill_brush };
        brush.clone().multiply_alpha(self.st.global_alpha)
    }

    /// Makes the clip layers open in the scene match the current clip list.
    /// Layers are opened lazily and kept open across draw calls; render() closes them.
    fn sync_clip(&mut self) {
        let common = self.open_clips.iter().zip(self.st.clips.iter())
            .take_while(|(open, clip)| **open == clip.id)
            .count();
        while self.open_clips.len() > common {
            self.scene.pop_layer();
            self.open_clips.pop();
        }
        for clip in &self.st.clips[common..] {
            self.scene.push_layer(clip.rule, BlendMode::default(), 1.0,
                                  Affine::IDENTITY, &*clip.path);
            self.open_clips.push(clip.id);
        }
    }

    /// Opens a layer for the global blend mode around one draw call.
    /// Returns true if a layer was pushed (the caller must pop it).
    fn push_blend(&mut self, bounds: Rect) -> bool {
        if self.st.blend == BlendMode::default() { return false; }
        self.scene.push_layer(Fill::NonZero, self.st.blend, 1.0, self.st.transform, &bounds);
        true
    }

    /// Fills a shape with the current fill paint, transform, alpha, blend mode and clip.
    fn fill_shape(&mut self, shape: &impl Shape, rule: Fill) {
        self.sync_clip();
        let blended = self.push_blend(shape.bounding_box());
        let brush = self.brush(false);
        self.scene.fill(rule, self.st.transform, &brush, None, shape);
        if blended { self.scene.pop_layer(); }
    }

    /// Strokes a shape with the current stroke paint, transform, alpha, blend mode and clip.
    fn stroke_shape(&mut self, shape: &impl Shape) {
        self.sync_clip();
        let stroke = self.stroke_style();
        let pad = self.st.line_width * self.st.miter_limit.max(1.0);
        let blended = self.push_blend(shape.bounding_box().inflate(pad, pad));
        let brush = self.brush(true);
        self.scene.stroke(&stroke, self.st.transform, &brush, None, shape);
        if blended { self.scene.pop_layer(); }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn create_render_context(width: u32, height: u32) -> *mut RenderContext {

    let shared = acquire_shared();
    let device = shared.device.clone();
    let queue = shared.queue.clone();

    // create a render target texture and a readback buffer
    let (texture, readback_buffer) = create_render_target(&device, width, height);

    let ctx = RenderContext {
        device,
        queue,
        texture,
        readback_buffer,
        width,
        height,
        scene: Scene::new(),
        st: State::new(),
        stack: Vec::new(),
        path: BezPath::new(),
        open_clips: Vec::new(),
        next_clip_id: 0,
        shared,
    };

    // detach from Rust's memory management and pass to Java as a raw pointer
    Box::into_raw(Box::new(ctx))
}

#[unsafe(no_mangle)]
pub extern "C" fn destroy_render_context(ctx_ptr: *mut RenderContext) {
    if !ctx_ptr.is_null() {
        // reconstruct the Box from the raw pointer to trigger
        // automatic dropping (memory deallocation) when going out of scope
        unsafe { let _ = Box::from_raw(ctx_ptr); }
        println!("RenderContext destroyed.");
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn render(ctx_ptr: *mut RenderContext, buffer: *mut u8) {

    if ctx_ptr.is_null() || buffer.is_null() {
        return;
    }

    // restore the reference from the raw pointer (without taking ownership)
    let ctx = unsafe { &mut *ctx_ptr };

    // the scene must be balanced: close the clip layers that are still open.
    // They are opened again lazily by the next draw call.
    while ctx.open_clips.pop().is_some() {
        ctx.scene.pop_layer();
    }

    let (device, queue, texture, readback_buffer, width, height) = (
        &ctx.device,
        &ctx.queue,
        &ctx.texture,
        &ctx.readback_buffer,
        ctx.width,
        ctx.height,
    );

    // draw on the GPU; the shared renderer is locked only while the scene is encoded
    {
        let mut renderer = lock(&ctx.shared.renderer);
        renderer.render_to_texture(
            device,
            queue,
            &ctx.scene,
            &texture.create_view(&wgpu::TextureViewDescriptor::default()),
            &vello::RenderParams {
                base_color: Color::TRANSPARENT,
                width, height,
                antialiasing_method: vello::AaConfig::Area,
            },
        ).expect("failed to render texture");
    }

    let row = row_bytes(width);
    let mut encoder = device.create_command_encoder(
        &wgpu::CommandEncoderDescriptor::default());
    encoder.copy_texture_to_buffer(
        wgpu::TexelCopyTextureInfo {
            texture,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::All,
        },
        wgpu::TexelCopyBufferInfo {
            buffer: readback_buffer,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(row),
                rows_per_image: Some(height),
            },
        },
        wgpu::Extent3d {
            width, height, depth_or_array_layers: 1
        },
    );
    queue.submit(Some(encoder.finish()));

    let buffer_slice = readback_buffer.slice(..);
    let (tx, rx) = std::sync::mpsc::channel();
    buffer_slice.map_async(wgpu::MapMode::Read, move |v| tx.send(v).unwrap());

    device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
    rx.recv().unwrap().unwrap();
    let mapped_data = buffer_slice.get_mapped_range().unwrap();

    // SIMD copy (RGBA -> BGRA)
    let src_bytes = unsafe { slice::from_raw_parts(mapped_data.as_ptr(), mapped_data.len()) };
    let dst_bytes = unsafe { slice::from_raw_parts_mut(buffer, mapped_data.len()) };
    for (src, dst) in src_bytes.chunks_exact(4).zip(dst_bytes.chunks_exact_mut(4)) {
        dst[0] = src[2]; // B <- R
        dst[1] = src[1]; // G <- G
        dst[2] = src[0]; // R <- B
        dst[3] = src[3]; // A <- A
    }

    drop(mapped_data);
    readback_buffer.unmap();
    ctx.scene.reset();
}

/// Resizes the canvas. The size must be non-zero and within the device limits.
/// Returns false if the size is invalid; the old size is kept in that case.
/// Call this before drawing a frame: commands already added to the scene are kept as they are.
/// After resizing, the buffer passed to render() must hold width * height * 4 bytes.
#[unsafe(no_mangle)]
pub extern "C" fn resize(ctx_ptr: *mut RenderContext, width: u32, height: u32) -> bool {
    if ctx_ptr.is_null() || width == 0 || height == 0 { return false; }
    let ctx = unsafe { &mut *ctx_ptr };
    if width == ctx.width && height == ctx.height { return true; }

    let max = ctx.device.limits().max_texture_dimension_2d;
    if width > max || height > max { return false; }

    // the old texture and buffer are dropped when replaced
    let (texture, readback_buffer) = create_render_target(&ctx.device, width, height);
    ctx.texture = texture;
    ctx.readback_buffer = readback_buffer;
    ctx.width = width;
    ctx.height = height;
    true
}

// -- state stack -------------------------------------------------------------

/// Pushes the current attributes (transform, paints, line style, font, alpha,
/// blend mode, fill rule, clip, text alignment) onto the state stack.
/// The current path is not saved.
#[unsafe(no_mangle)]
pub extern "C" fn save_state(ctx_ptr: *mut RenderContext) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.stack.push(ctx.st.clone());
}

/// Pops the attributes pushed by save_state(). Does nothing if the stack is empty.
/// Clip layers that are no longer active are closed lazily by the next draw call.
#[unsafe(no_mangle)]
pub extern "C" fn restore_state(ctx_ptr: *mut RenderContext) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    if let Some(st) = ctx.stack.pop() {
        ctx.st = st;
    }
}

// -- attribute ---------------------------------------------------------------

/// Sets the current fill paint attribute to a solid color. The default value is BLACK.
#[unsafe(no_mangle)]
pub extern "C" fn set_fill(ctx_ptr: *mut RenderContext, r: u8, g: u8, b: u8, a: u8) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.fill_brush = Brush::Solid(Color::from_rgba8(r, g, b, a));
}

/// Sets the current stroke paint attribute to a solid color. The default value is BLACK.
#[unsafe(no_mangle)]
pub extern "C" fn set_stroke(ctx_ptr: *mut RenderContext, r: u8, g: u8, b: u8, a: u8) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.stroke_brush = Brush::Solid(Color::from_rgba8(r, g, b, a));
}

/// Sets a linear gradient as the fill (target = 0) or stroke (target = 1) paint.
/// The coordinates are in user space (the transform at draw time applies to them).
/// extend: 0 = pad (no cycle), 1 = reflect, 2 = repeat.
/// offsets holds n stop positions (0..1), colors holds n RGBA quadruples.
/// Returns false if the arguments are invalid; the paint is unchanged in that case.
#[unsafe(no_mangle)]
pub extern "C" fn set_linear_gradient(ctx_ptr: *mut RenderContext, target: u32,
                                      x0: f64, y0: f64, x1: f64, y1: f64, extend: u32,
                                      offsets: *const f32, colors: *const u8, n: u32) -> bool {
    if ctx_ptr.is_null() { return false; }
    let ctx = unsafe { &mut *ctx_ptr };
    let Some(stops) = (unsafe { read_stops(offsets, colors, n) }) else { return false; };
    let gradient = Gradient::new_linear((x0, y0), (x1, y1))
        .with_extend(extend_of(extend))
        .with_stops(stops.as_slice());
    set_brush(ctx, target, Brush::Gradient(gradient));
    true
}

/// Sets a radial gradient as the fill (target = 0) or stroke (target = 1) paint.
/// The first stop is mapped to the focus point (fx, fy), the last one to the circle
/// of the given center and radius, like JavaFX RadialGradient.
/// See set_linear_gradient() for the other arguments.
#[unsafe(no_mangle)]
pub extern "C" fn set_radial_gradient(ctx_ptr: *mut RenderContext, target: u32,
                                      cx: f64, cy: f64, radius: f64, fx: f64, fy: f64, extend: u32,
                                      offsets: *const f32, colors: *const u8, n: u32) -> bool {
    if ctx_ptr.is_null() || !(radius > 0.0) { return false; }
    let ctx = unsafe { &mut *ctx_ptr };
    let Some(stops) = (unsafe { read_stops(offsets, colors, n) }) else { return false; };
    let gradient = Gradient::new_two_point_radial((fx, fy), 0.0_f32, (cx, cy), radius as f32)
        .with_extend(extend_of(extend))
        .with_stops(stops.as_slice());
    set_brush(ctx, target, Brush::Gradient(gradient));
    true
}

/// Sets the global alpha (0.0 - 1.0) that is applied to everything drawn afterward.
/// Out-of-range or non-finite values are ignored.
#[unsafe(no_mangle)]
pub extern "C" fn set_global_alpha(ctx_ptr: *mut RenderContext, alpha: f64) {
    if ctx_ptr.is_null() || !alpha.is_finite() || !(0.0..=1.0).contains(&alpha) { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.global_alpha = alpha as f32;
}

/// Sets the global blend mode. Codes:
/// 0 SRC_OVER, 1 SRC_ATOP, 2 ADD, 3 MULTIPLY, 4 SCREEN, 5 OVERLAY, 6 DARKEN, 7 LIGHTEN,
/// 8 COLOR_DODGE, 9 COLOR_BURN, 10 HARD_LIGHT, 11 SOFT_LIGHT, 12 DIFFERENCE, 13 EXCLUSION.
/// Unknown values are ignored.
#[unsafe(no_mangle)]
pub extern "C" fn set_blend_mode(ctx_ptr: *mut RenderContext, mode: u32) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    let mix = |m| BlendMode::new(m, Compose::SrcOver);
    ctx.st.blend = match mode {
        0 => BlendMode::default(),
        1 => BlendMode::new(Mix::Normal, Compose::SrcAtop),
        2 => BlendMode::new(Mix::Normal, Compose::Plus),
        3 => mix(Mix::Multiply),
        4 => mix(Mix::Screen),
        5 => mix(Mix::Overlay),
        6 => mix(Mix::Darken),
        7 => mix(Mix::Lighten),
        8 => mix(Mix::ColorDodge),
        9 => mix(Mix::ColorBurn),
        10 => mix(Mix::HardLight),
        11 => mix(Mix::SoftLight),
        12 => mix(Mix::Difference),
        13 => mix(Mix::Exclusion),
        _ => return,
    };
}

/// Sets the fill rule used by fill_path(), fill_polygon() and clip_path():
/// 0 = non-zero (the default), 1 = even-odd. Unknown values are ignored.
#[unsafe(no_mangle)]
pub extern "C" fn set_fill_rule(ctx_ptr: *mut RenderContext, rule: u32) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.fill_rule = match rule {
        0 => Fill::NonZero,
        1 => Fill::EvenOdd,
        _ => return,
    };
}

/// Sets the transform: x' = a * x + c * y + e, y' = b * x + d * y + f.
/// It applies to everything drawn afterward (the path is kept in user space
/// and transformed when it is filled / stroked / used as a clip).
#[unsafe(no_mangle)]
pub extern "C" fn set_transform(ctx_ptr: *mut RenderContext,
                                a: f64, b: f64, c: f64, d: f64, e: f64, f: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.transform = Affine::new([a, b, c, d, e, f]);
}

/// Sets the width; it applies to all following stroke operations
#[unsafe(no_mangle)]
pub extern "C" fn set_line_width(ctx_ptr: *mut RenderContext, line_width: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.line_width = line_width;
}

/// Sets the line cap: 0 = butt, 1 = round, 2 = square.
/// Unknown values are ignored.
#[unsafe(no_mangle)]
pub extern "C" fn set_line_cap(ctx_ptr: *mut RenderContext, cap: u32) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.line_cap = match cap {
        0 => Cap::Butt,
        1 => Cap::Round,
        2 => Cap::Square,
        _ => return,
    };
}

/// Sets the line join: 0 = Bevel, 1 = round, 2 = Miter.
/// Unknown values are ignored.
#[unsafe(no_mangle)]
pub extern "C" fn set_line_join(ctx_ptr: *mut RenderContext, join: u32) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.line_join = match join {
        0 => Join::Bevel,
        1 => Join::Round,
        2 => Join::Miter,
        _ => return,
    };
}

/// Sets the miter limit (the default is 10). Non-positive or non-finite values are ignored.
#[unsafe(no_mangle)]
pub extern "C" fn set_miter_limit(ctx_ptr: *mut RenderContext, limit: f64) {
    if ctx_ptr.is_null() || !limit.is_finite() || limit <= 0.0 { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.miter_limit = limit;
}

/// Sets the dash pattern of strokes: alternating dash and gap lengths.
/// n = 0 (or a null pointer) turns dashing off.
/// The caller must validate the values (non-negative, finite, not all zero).
#[unsafe(no_mangle)]
pub extern "C" fn set_line_dashes(ctx_ptr: *mut RenderContext, dashes: *const f64, n: u32) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.dashes = if dashes.is_null() || n == 0 {
        Vec::new()
    } else {
        unsafe { slice::from_raw_parts(dashes, n as usize) }.to_vec()
    };
}

/// Sets the offset into the dash pattern.
#[unsafe(no_mangle)]
pub extern "C" fn set_line_dash_offset(ctx_ptr: *mut RenderContext, offset: f64) {
    if ctx_ptr.is_null() || !offset.is_finite() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.dash_offset = offset;
}

/// Loads a font file (TTF, OTF, TTC, OTC) and registers it in the shared font collection.
/// Returns the number of font faces registered, or 0 on failure
/// (null pointer, illegal UTF-8, unreadable file, or no valid font in the data).
/// The family name(s) of the registered font are written to name_buf as a NUL-terminated
/// UTF-8 string, so the caller can pass it to set_font_family().
/// If the file holds several families (a collection file), the names are separated by '\n'.
/// name_buf may be null (the names are not returned). If name_cap is too small,
/// the name is truncated at a character boundary. On failure an empty string is written.
/// The collection is shared, so the font is visible to all RenderContexts
/// and stays registered until release_shared_resources() is called.
#[unsafe(no_mangle)]
pub extern "C" fn load_font_file(path_ptr: *const c_char,
                                 name_buf: *mut u8, name_cap: usize) -> u32 {
    unsafe { write_c_string("", name_buf, name_cap); }
    if path_ptr.is_null() { return 0; }
    let c_str = unsafe { CStr::from_ptr(path_ptr) };
    let Ok(path) = c_str.to_str() else { return 0; }; // illegal UTF-8
    let Ok(data) = std::fs::read(path) else { return 0; };
    register_font_bytes(data, name_buf, name_cap)
}

/// Registers font data held in memory, like load_font_file().
/// The data is copied, so the caller may free it's buffer after this call.
/// Returns the number of font faces registered, or 0 on failure.
#[unsafe(no_mangle)]
pub extern "C" fn load_font_data(data_ptr: *const u8, len: usize,
                                 name_buf: *mut u8, name_cap: usize) -> u32 {
    unsafe { write_c_string("", name_buf, name_cap); }
    if data_ptr.is_null() || len == 0 { return 0; }
    let data = unsafe { slice::from_raw_parts(data_ptr, len) }.to_vec();
    register_font_bytes(data, name_buf, name_cap)
}

/// Sets the font families as a comma-separated list, e.g. "Segoe UI, Meiryo, sans-serif".
/// Characters missing in a family fall back to the next one, then to other system fonts.
/// Returns false if none of the listed (non-generic) families is installed;
/// the list is stored anyway.
#[unsafe(no_mangle)]
pub extern "C" fn set_font_family(ctx_ptr: *mut RenderContext, names_ptr: *const c_char) -> bool {
    if ctx_ptr.is_null() || names_ptr.is_null() { return false; }
    let ctx = unsafe { &mut *ctx_ptr };
    let c_str = unsafe { CStr::from_ptr(names_ptr) };
    let names = match c_str.to_str() {
        Ok(s) => s,
        Err(_) => return false, // illegal UTF-8
    };
    ctx.st.font_family = names.trim().to_string();

    // the font collection is shared, so it must be locked while it is queried
    let mut text = lock(&ctx.shared.text);

    // generic names (serif, sans-serif, ...) are resolved by the system and always count as found
    names.split(',')
        .map(|n| n.trim().trim_matches(|c| c == '"' || c == '\''))
        .filter(|n| !n.is_empty())
        .any(|n| {
            matches!(
                n.to_ascii_lowercase().as_str(),
                "serif" | "sans-serif" | "monospace" | "cursive" | "fantasy" | "system-ui"
            ) || text.font_cx.collection.family_id(n).is_some()
        })
}

/// Sets the font size in pixels. Non-positive or non-finite values are ignored.
#[unsafe(no_mangle)]
pub extern "C" fn set_font_size(ctx_ptr: *mut RenderContext, size: f64) {
    if ctx_ptr.is_null() || !size.is_finite() || size <= 0.0 { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.font_size = size as f32;
}

/// Sets the font weight on the CSS scale: 100 (thin) to 900 (black).
/// 400 is normal (the default) and 700 is bold. Out-of-range or non-finite values are ignored.
/// Fonts that are not variable only have a few weights (often 400 and 700),
/// so an intermediate value may select the nearest available face.
#[unsafe(no_mangle)]
pub extern "C" fn set_font_weight(ctx_ptr: *mut RenderContext, weight: f64) {
    if ctx_ptr.is_null() || !weight.is_finite() || !(1.0..=1000.0).contains(&weight) { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.font_weight = weight as f32;
}

/// Sets the horizontal text anchor: 0 = left (the default), 1 = center, 2 = right.
/// The x given to fill_text() / stroke_text() is then the left end, the center or the right end
/// of the text. Unknown values are ignored.
#[unsafe(no_mangle)]
pub extern "C" fn set_text_align(ctx_ptr: *mut RenderContext, align: u32) {
    if ctx_ptr.is_null() || align > 2 { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.text_align = align;
}

/// Sets the vertical text anchor: 0 = baseline (the default), 1 = top, 2 = center, 3 = bottom.
/// The y given to fill_text() / stroke_text() is then the baseline, or the top / center /
/// bottom of the line box. Unknown values are ignored.
#[unsafe(no_mangle)]
pub extern "C" fn set_text_baseline(ctx_ptr: *mut RenderContext, baseline: u32) {
    if ctx_ptr.is_null() || baseline > 3 { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.st.text_baseline = baseline;
}

// -- measure -----------------------------------------------------------------

/// Returns the advance width of a single Unicode code point in the current font.
/// Returns 0.0 for an invalid code point (surrogates, > 0x10FFFF) or a null context.
#[unsafe(no_mangle)]
pub extern "C" fn get_advance(ctx_ptr: *mut RenderContext, code_point: u32) -> f64 {
    if ctx_ptr.is_null() { return 0.0; }
    let ctx = unsafe { &*ctx_ptr };
    let Some(ch) = char::from_u32(code_point) else { return 0.0; };
    let mut buf = [0u8; 4];
    measure_width(ctx, ch.encode_utf8(&mut buf))
}

/// Returns the advance width of a NUL-terminated UTF-8 string in the current font.
/// The string is measured as a single line, so it is the same width fill_text() draws.
/// Returns 0.0 for illegal UTF-8, an empty string or a null pointer.
#[unsafe(no_mangle)]
pub extern "C" fn get_text_advance(ctx_ptr: *mut RenderContext, text_ptr: *const c_char) -> f64 {
    if ctx_ptr.is_null() || text_ptr.is_null() { return 0.0; }
    let ctx = unsafe { &*ctx_ptr };
    let c_str = unsafe { CStr::from_ptr(text_ptr) };
    let Ok(text) = c_str.to_str() else { return 0.0; };
    measure_width(ctx, text)
}

/// Returns the line metrics of the current font.
/// A reference character is laid out, so the value follows the first available family
/// in the font list. A line containing fallback fonts may be taller when drawn.
#[unsafe(no_mangle)]
pub extern "C" fn get_line_metrics(ctx_ptr: *mut RenderContext) -> Metrics {
    if ctx_ptr.is_null() { return Metrics::default(); }
    let ctx = unsafe { &*ctx_ptr };
    let layout = build_layout(ctx, "M");
    let Some(line) = layout.lines().next() else { return Metrics::default(); };
    let metrics = line.metrics();
    Metrics {
        ascent: metrics.ascent as f64,
        descent: metrics.descent as f64,
        leading: metrics.leading as f64,
        line_height: metrics.line_height as f64,
        baseline: metrics.baseline as f64,
        advance: metrics.advance as f64,
    }
}

// -- draw --------------------------------------------------------------------

/// Fills a rectangle using the current fill paint.
#[unsafe(no_mangle)]
pub extern "C" fn fill_rect(ctx_ptr: *mut RenderContext,
                            x: f64, y: f64, width: f64, height: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.fill_shape(&Rect::new(x, y, x + width, y + height), Fill::NonZero);
}

#[unsafe(no_mangle)]
pub extern "C" fn stroke_rect(ctx_ptr: *mut RenderContext,
                              x: f64, y: f64, width: f64, height: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.stroke_shape(&Rect::new(x, y, x + width, y + height));
}

/// Fills an oval inscribed in the given bounding rectangle using the current fill paint.
#[unsafe(no_mangle)]
pub extern "C" fn fill_oval(ctx_ptr: *mut RenderContext,
                            x: f64, y: f64, width: f64, height: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.fill_shape(&oval(x, y, width, height), Fill::NonZero);
}

/// Strokes an oval inscribed in the given bounding rectangle using the current stroke paint and width.
#[unsafe(no_mangle)]
pub extern "C" fn stroke_oval(ctx_ptr: *mut RenderContext,
                              x: f64, y: f64, width: f64, height: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.stroke_shape(&oval(x, y, width, height));
}

/// Fills a rounded rectangle using the current fill paint.
/// radius is the radius of the corner arcs.
#[unsafe(no_mangle)]
pub extern "C" fn fill_round_rect(ctx_ptr: *mut RenderContext,
                                  x: f64, y: f64, width: f64, height: f64, radius: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.fill_shape(&RoundedRect::new(x, y, x + width, y + height, radius), Fill::NonZero);
}

/// Strokes a rounded rectangle using the current stroke paint and width.
/// radius is the radius of the corner arcs.
#[unsafe(no_mangle)]
pub extern "C" fn stroke_round_rect(ctx_ptr: *mut RenderContext,
                                    x: f64, y: f64, width: f64, height: f64, radius: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.stroke_shape(&RoundedRect::new(x, y, x + width, y + height, radius));
}

/// Strokes a line from (x1, y1) to (x2, y2) using the current stroke paint and width.
#[unsafe(no_mangle)]
pub extern "C" fn stroke_line(ctx_ptr: *mut RenderContext,
                              x1: f64, y1: f64, x2: f64, y2: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.stroke_shape(&Line::new((x1, y1), (x2, y2)));
}

/// Fills an arc with the ellipse inscribed in the given bounding rectangle.
/// Angles are in degrees, measured counter-clockwise from the positive x-axis (JavaFX style).
/// arc_type: 0 = open (a fill closes it with a chord), 1 = chord, 2 = round (pie slice).
#[unsafe(no_mangle)]
pub extern "C" fn fill_arc(ctx_ptr: *mut RenderContext,
                           x: f64, y: f64, width: f64, height: f64, start: f64, extent: f64, arc_type: u32) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    let path = arc_shape(x, y, width, height, start, extent, arc_type);
    let rule = ctx.st.fill_rule;
    ctx.fill_shape(&path, rule);
}

/// Strokes an arc, see fill_arc(). An open arc is not closed by the stroke.
#[unsafe(no_mangle)]
pub extern "C" fn stroke_arc(ctx_ptr: *mut RenderContext,
                             x: f64, y: f64, width: f64, height: f64, start: f64, extent: f64, arc_type: u32) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    let path = arc_shape(x, y, width, height, start, extent, arc_type);
    ctx.stroke_shape(&path);
}

/// Fills a closed polygon using the current fill paint and fill rule
/// (non-zero unless set_fill_rule() was called, same as JavaFX).
#[unsafe(no_mangle)]
pub extern "C" fn fill_polygon(ctx_ptr: *mut RenderContext,
                               x_points: *const f64, y_points: *const f64, n_points: u32) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    let Some(path) = (unsafe { points_to_path(x_points, y_points, n_points, true) }) else { return; };
    let rule = ctx.st.fill_rule;
    ctx.fill_shape(&path, rule);
}

/// Strokes a closed polygon using the current stroke paint, width and cap.
#[unsafe(no_mangle)]
pub extern "C" fn stroke_polygon(ctx_ptr: *mut RenderContext,
                                 x_points: *const f64, y_points: *const f64, n_points: u32) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    let Some(path) = (unsafe { points_to_path(x_points, y_points, n_points, true) }) else { return; };
    ctx.stroke_shape(&path);
}

/// Strokes an open polyline (the last point is not connected to the first one)
/// using the current stroke paint, width and cap.
#[unsafe(no_mangle)]
pub extern "C" fn stroke_polyline(ctx_ptr: *mut RenderContext,
                                  x_points: *const f64, y_points: *const f64, n_points: u32) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    let Some(path) = (unsafe { points_to_path(x_points, y_points, n_points, false) }) else { return; };
    ctx.stroke_shape(&path);
}

/// Draws UTF-8 text filled with the current fill paint.
/// (x, y) is the anchor of the first line: the baseline origin by default,
/// see set_text_align() / set_text_baseline().
/// Returns the bottom-right corner of the drawn text.
/// If nothing is drawn (null pointer, illegal UTF-8, empty text), (x, y) is returned.
#[unsafe(no_mangle)]
pub extern "C" fn fill_text(ctx_ptr: *mut RenderContext,
                            text_ptr: *const c_char, x: f64, y: f64) -> Point {
    if ctx_ptr.is_null() || text_ptr.is_null() { return Point { x, y }; }
    let c_str = unsafe { CStr::from_ptr(text_ptr) };
    let text = match c_str.to_str() {
        Ok(s) => s,
        Err(_) => return Point { x, y }, // illegal UTF-8
    };
    draw_text_internal(ctx_ptr, text, x, y, false, f64::INFINITY)
}

/// Draws UTF-8 text filled with the current fill paint, like fill_text().
/// If the text is wider than max_width, it is scaled horizontally to fit
/// (the anchor stays at (x, y)).
/// Nothing is drawn if max_width is not positive or is NaN.
/// Returns the bottom-right corner of the drawn text (after scaling).
#[unsafe(no_mangle)]
pub extern "C" fn fill_text_max_width(ctx_ptr: *mut RenderContext,
                                      text_ptr: *const c_char, x: f64, y: f64, max_width: f64) -> Point {
    if ctx_ptr.is_null() || text_ptr.is_null() { return Point { x, y }; }
    let c_str = unsafe { CStr::from_ptr(text_ptr) };
    let text = match c_str.to_str() {
        Ok(s) => s,
        Err(_) => return Point { x, y }, // illegal UTF-8
    };
    draw_text_internal(ctx_ptr, text, x, y, false, max_width)
}

/// Draws the outline of UTF-8 text using the current stroke paint and width.
/// Cap and join are also applied, same as the other stroke operations.
/// Returns the bottom-right corner of the text layout (the stroke width is not included),
/// so it is the same point fill_text() returns for the same text.
/// If nothing is drawn (null pointer, illegal UTF-8, empty text), (x, y) is returned.
#[unsafe(no_mangle)]
pub extern "C" fn stroke_text(ctx_ptr: *mut RenderContext,
                              text_ptr: *const c_char, x: f64, y: f64) -> Point {
    if ctx_ptr.is_null() || text_ptr.is_null() { return Point { x, y }; }
    let c_str = unsafe { CStr::from_ptr(text_ptr) };
    let text = match c_str.to_str() {
        Ok(s) => s,
        Err(_) => return Point { x, y }, // illegal UTF-8
    };
    draw_text_internal(ctx_ptr, text, x, y, true, f64::INFINITY)
}

/// Draws the outline of UTF-8 text, like stroke_text().
/// If the text is wider than max_width, it is scaled horizontally to fit
/// (the anchor stays at (x, y)).
/// The scaling is applied to the stroke too, so the line gets thinner horizontally.
/// Nothing is drawn if max_width is not positive or is NaN.
/// Returns the bottom-right corner of the text layout (after scaling).
#[unsafe(no_mangle)]
pub extern "C" fn stroke_text_max_width(ctx_ptr: *mut RenderContext,
                                        text_ptr: *const c_char, x: f64, y: f64, max_width: f64) -> Point {
    if ctx_ptr.is_null() || text_ptr.is_null() { return Point { x, y }; }
    let c_str = unsafe { CStr::from_ptr(text_ptr) };
    let text = match c_str.to_str() {
        Ok(s) => s,
        Err(_) => return Point { x, y }, // illegal UTF-8
    };
    draw_text_internal(ctx_ptr, text, x, y, true, max_width)
}

/// Clears a rectangle to transparent black.
/// The current fill paint is ignored: the pixels inside the rectangle
/// (including what was drawn earlier in this frame) become fully transparent.
/// The transform and the clip apply.
#[unsafe(no_mangle)]
pub extern "C" fn clear_rect(ctx_ptr: *mut RenderContext,
                             x: f64, y: f64, width: f64, height: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    let rect = Rect::new(x, y, x + width, y + height);

    ctx.sync_clip();
    // a layer whose blend mode is Clear erases the backdrop inside the clip shape;
    // the color drawn inside does not matter, but the layer must not be empty
    // so that the clip is actually composited
    ctx.scene.push_layer(
        Fill::NonZero,
        BlendMode::new(Mix::Normal, Compose::Clear),
        1.0,
        ctx.st.transform,
        &rect,
    );
    ctx.scene.fill(
        Fill::NonZero,
        ctx.st.transform,
        Color::BLACK,
        None,
        &rect,
    );
    ctx.scene.pop_layer();
}

// -- path --------------------------------------------------------------------
// The current path lives in user space and is transformed when it is used.
// (JavaFX / HTML canvas transform the points when they are added. The result is
// the same as long as the transform is not changed between building and drawing.)

/// Starts a new, empty path.
#[unsafe(no_mangle)]
pub extern "C" fn begin_path(ctx_ptr: *mut RenderContext) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.path = BezPath::new();
}

/// Starts a new sub-path at (x, y).
#[unsafe(no_mangle)]
pub extern "C" fn move_to(ctx_ptr: *mut RenderContext, x: f64, y: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.path.move_to((x, y));
}

/// Adds a line to (x, y). Works as move_to() if the path is empty.
#[unsafe(no_mangle)]
pub extern "C" fn line_to(ctx_ptr: *mut RenderContext, x: f64, y: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    if ctx.path.elements().is_empty() {
        ctx.path.move_to((x, y));
    } else {
        ctx.path.line_to((x, y));
    }
}

/// Adds a quadratic Bézier curve with the control point (cx, cy) ending at (x, y).
#[unsafe(no_mangle)]
pub extern "C" fn quad_to(ctx_ptr: *mut RenderContext, cx: f64, cy: f64, x: f64, y: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    if ctx.path.elements().is_empty() {
        ctx.path.move_to((cx, cy));
    }
    ctx.path.quad_to((cx, cy), (x, y));
}

/// Adds a cubic Bézier curve with the control points (c1x, c1y), (c2x, c2y) ending at (x, y).
#[unsafe(no_mangle)]
pub extern "C" fn bezier_to(ctx_ptr: *mut RenderContext,
                            c1x: f64, c1y: f64, c2x: f64, c2y: f64, x: f64, y: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    if ctx.path.elements().is_empty() {
        ctx.path.move_to((c1x, c1y));
    }
    ctx.path.curve_to((c1x, c1y), (c2x, c2y), (x, y));
}

/// Adds an arc tangent to the lines (current point -> (x1, y1)) and ((x1, y1) -> (x2, y2)),
/// like HTML canvas arcTo(). A line is added from the current point to the arc start.
#[unsafe(no_mangle)]
pub extern "C" fn arc_to(ctx_ptr: *mut RenderContext,
                         x1: f64, y1: f64, x2: f64, y2: f64, radius: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    let p1 = KPoint::new(x1, y1);
    let p2 = KPoint::new(x2, y2);
    let Some(p0) = current_point(&ctx.path) else {
        ctx.path.move_to(p1);
        return;
    };

    let (d0, d1) = (p0 - p1, p2 - p1);
    let (l0, l1) = (d0.hypot(), d1.hypot());
    // degenerate cases: just draw a line to p1
    if l0 < 1e-12 || l1 < 1e-12 || !(radius > 0.0) {
        ctx.path.line_to(p1);
        return;
    }
    let (u0, u1) = (d0 / l0, d1 / l1);
    if u0.cross(u1).abs() < 1e-9 {
        ctx.path.line_to(p1);
        return;
    }

    // half of the angle between the two lines
    let half = u0.dot(u1).clamp(-1.0, 1.0).acos() / 2.0;
    // distance from p1 to the tangent points, and the arc center on the bisector
    let t = radius / half.tan();
    let start = p1 + u0 * t;
    let end = p1 + u1 * t;
    let center = p1 + (u0 + u1).normalize() * (radius / half.sin());

    // the arc is always shorter than half a circle, so the shortest sweep is the right one
    let a0 = (start - center).atan2();
    let a1 = (end - center).atan2();
    let mut sweep = a1 - a0;
    while sweep > PI { sweep -= 2.0 * PI; }
    while sweep < -PI { sweep += 2.0 * PI; }

    ctx.path.line_to(start);
    let arc = KArc::new(center, (radius, radius), a0, sweep, 0.0);
    for el in arc.path_elements(0.05).skip(1) { // skip the MoveTo, we are already there
        ctx.path.push(el);
    }
}

/// Adds an elliptical arc to the path (JavaFX arc()).
/// Angles are in degrees, counter-clockwise from the positive x-axis;
/// a positive length goes counter-clockwise. If the path is not empty,
/// a line is added from the current point to the arc start.
#[unsafe(no_mangle)]
pub extern "C" fn path_arc(ctx_ptr: *mut RenderContext,
                           cx: f64, cy: f64, rx: f64, ry: f64, start: f64, length: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    // y points down on the screen, so the angles are negated
    let arc = KArc::new((cx, cy), (rx, ry), -start.to_radians(), -length.to_radians(), 0.0);
    for el in arc.path_elements(0.05) {
        match el {
            PathEl::MoveTo(p) if ctx.path.elements().is_empty() => ctx.path.move_to(p),
            PathEl::MoveTo(p) => ctx.path.line_to(p),
            other => ctx.path.push(other),
        }
    }
}

/// Adds a closed rectangle sub-path.
#[unsafe(no_mangle)]
pub extern "C" fn path_rect(ctx_ptr: *mut RenderContext, x: f64, y: f64, w: f64, h: f64) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    ctx.path.extend(Rect::new(x, y, x + w, y + h).path_elements(0.1));
}

/// Closes the current sub-path. Does nothing if the path is empty.
#[unsafe(no_mangle)]
pub extern "C" fn close_path(ctx_ptr: *mut RenderContext) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    if !ctx.path.elements().is_empty() {
        ctx.path.close_path();
    }
}

/// Appends an SVG path string (e.g. "M 10 10 L 50 50 Z") to the current path.
/// Returns false if the string cannot be parsed; the path is unchanged in that case.
#[unsafe(no_mangle)]
pub extern "C" fn append_svg_path(ctx_ptr: *mut RenderContext, svg_ptr: *const c_char) -> bool {
    if ctx_ptr.is_null() || svg_ptr.is_null() { return false; }
    let ctx = unsafe { &mut *ctx_ptr };
    let c_str = unsafe { CStr::from_ptr(svg_ptr) };
    let Ok(svg) = c_str.to_str() else { return false; };
    let Ok(parsed) = BezPath::from_svg(svg) else { return false; };
    ctx.path.extend(parsed.elements().iter().copied());
    true
}

/// Fills the current path using the current fill paint and fill rule.
/// The path is kept, so it can be stroked afterward.
#[unsafe(no_mangle)]
pub extern "C" fn fill_path(ctx_ptr: *mut RenderContext) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    let rule = ctx.st.fill_rule;
    let path = std::mem::take(&mut ctx.path);
    ctx.fill_shape(&path, rule);
    ctx.path = path;
}

/// Strokes the current path using the current stroke paint and line style.
#[unsafe(no_mangle)]
pub extern "C" fn stroke_path(ctx_ptr: *mut RenderContext) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    let path = std::mem::take(&mut ctx.path);
    ctx.stroke_shape(&path);
    ctx.path = path;
}

/// Intersects the clip with the current path (using the current transform and fill rule).
/// The clip lasts until the matching restore_state(). An empty path is ignored.
#[unsafe(no_mangle)]
pub extern "C" fn clip_path(ctx_ptr: *mut RenderContext) {
    if ctx_ptr.is_null() { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    if ctx.path.elements().is_empty() { return; }
    let mut device_path = ctx.path.clone();
    device_path.apply_affine(ctx.st.transform);
    ctx.next_clip_id += 1;
    let clip = Clip {
        id: ctx.next_clip_id,
        rule: ctx.st.fill_rule,
        path: Arc::new(device_path),
    };
    ctx.st.clips.push(clip);
}

/// Returns true if the point (x, y) (user space) is inside the current path,
/// judged with the current fill rule.
#[unsafe(no_mangle)]
pub extern "C" fn is_point_in_path(ctx_ptr: *mut RenderContext, x: f64, y: f64) -> bool {
    if ctx_ptr.is_null() { return false; }
    let ctx = unsafe { &*ctx_ptr };
    let winding = ctx.path.winding(KPoint::new(x, y));
    match ctx.st.fill_rule {
        Fill::NonZero => winding != 0,
        Fill::EvenOdd => winding % 2 != 0,
    }
}

// -- image -------------------------------------------------------------------

/// Registers an image and returns its handle (0 on failure).
/// bgra_ptr points to width * height * 4 bytes of non-premultiplied BGRA pixels
/// (JavaFX PixelFormat.getByteBgraInstance()). The data is copied.
/// The handle is valid for all RenderContexts until destroy_image() is called.
#[unsafe(no_mangle)]
pub extern "C" fn create_image(bgra_ptr: *const u8, width: u32, height: u32) -> u64 {
    if bgra_ptr.is_null() || width == 0 || height == 0 { return 0; }
    let len = width as usize * height as usize * 4;
    let src = unsafe { slice::from_raw_parts(bgra_ptr, len) };
    let mut rgba = Vec::with_capacity(len);
    for px in src.chunks_exact(4) {
        rgba.extend_from_slice(&[px[2], px[1], px[0], px[3]]);
    }
    let image = ImageBrush::new(ImageData {
        data: Blob::from(rgba),
        format: ImageFormat::Rgba8,
        alpha_type: ImageAlphaType::Alpha,
        width,
        height,
    });
    let mut store = lock(&IMAGES);
    store.next += 1;
    let id = store.next;
    store.map.insert(id, image);
    id
}

/// Releases an image registered by create_image(). Unknown handles are ignored.
#[unsafe(no_mangle)]
pub extern "C" fn destroy_image(image_id: u64) {
    lock(&IMAGES).map.remove(&image_id);
}

/// Draws the source rectangle (sx, sy, sw, sh) of the image (in image pixels)
/// into the destination rectangle (dx, dy, dw, dh), scaling as needed.
/// The transform, global alpha, blend mode and clip apply.
#[unsafe(no_mangle)]
pub extern "C" fn draw_image(ctx_ptr: *mut RenderContext, image_id: u64,
                             sx: f64, sy: f64, sw: f64, sh: f64, dx: f64, dy: f64, dw: f64, dh: f64) {
    if ctx_ptr.is_null() || !(sw > 0.0) || !(sh > 0.0) || !(dw > 0.0) || !(dh > 0.0) { return; }
    let ctx = unsafe { &mut *ctx_ptr };
    let Some(image) = lock(&IMAGES).map.get(&image_id).cloned() else { return; };

    ctx.sync_clip();
    // maps the source rectangle onto the destination rectangle
    let local = ctx.st.transform
        * Affine::translate((dx, dy))
        * Affine::scale_non_uniform(dw / sw, dh / sh)
        * Affine::translate((-sx, -sy));
    // the layer crops to the destination rectangle and carries the alpha and the blend mode
    let dst = Rect::new(dx, dy, dx + dw, dy + dh);
    ctx.scene.push_layer(Fill::NonZero, ctx.st.blend, ctx.st.global_alpha, ctx.st.transform, &dst);
    ctx.scene.draw_image(&image, local);
    ctx.scene.pop_layer();
}

// -- private -----------------------------------------------------------------

fn oval(x: f64, y: f64, width: f64, height: f64) -> Ellipse {
    // center is the middle of the bounding box, radii are half of its size
    Ellipse::new((x + width / 2.0, y + height / 2.0), (width / 2.0, height / 2.0), 0.0)
}

/// Builds the path of an arc inscribed in the bounding rectangle (JavaFX fillArc / strokeArc).
fn arc_shape(x: f64, y: f64, width: f64, height: f64,
             start: f64, extent: f64, arc_type: u32) -> BezPath {
    let (cx, cy) = (x + width / 2.0, y + height / 2.0);
    // y points down on the screen, so the angles are negated
    let arc = KArc::new((cx, cy), (width / 2.0, height / 2.0),
                        -start.to_radians(), -extent.to_radians(), 0.0);
    let round = arc_type == 2;
    let mut path = BezPath::new();
    if round {
        path.move_to((cx, cy));
    }
    for el in arc.path_elements(0.05) {
        match el {
            PathEl::MoveTo(p) if round => path.line_to(p), // center -> arc start
            other => path.push(other),
        }
    }
    if arc_type != 0 {
        path.close_path();
    }
    path
}

/// The current point of a path (the end of the last segment, or the start of the
/// sub-path after a close), or None for an empty path.
fn current_point(path: &BezPath) -> Option<KPoint> {
    let els = path.elements();
    match els.last()? {
        PathEl::MoveTo(p) | PathEl::LineTo(p)
        | PathEl::QuadTo(_, p) | PathEl::CurveTo(_, _, p) => Some(*p),
        PathEl::ClosePath => els.iter().rev().find_map(|e| match e {
            PathEl::MoveTo(p) => Some(*p),
            _ => None,
        }),
    }
}

fn set_brush(ctx: &mut RenderContext, target: u32, brush: Brush) {
    if target == 0 {
        ctx.st.fill_brush = brush;
    } else {
        ctx.st.stroke_brush = brush;
    }
}

fn extend_of(code: u32) -> Extend {
    match code {
        1 => Extend::Reflect,
        2 => Extend::Repeat,
        _ => Extend::Pad,
    }
}

/// Reads n gradient stops (offset + RGBA color). Returns None for null pointers or n = 0.
/// The caller must guarantee that offsets is valid for n floats and colors for n * 4 bytes.
unsafe fn read_stops(offsets: *const f32, colors: *const u8, n: u32) -> Option<Vec<(f32, Color)>> {
    if offsets.is_null() || colors.is_null() || n == 0 { return None; }
    let n = n as usize;
    let offsets = unsafe { slice::from_raw_parts(offsets, n) };
    let colors = unsafe { slice::from_raw_parts(colors, n * 4) };
    Some(offsets.iter().zip(colors.chunks_exact(4))
        .map(|(o, c)| (o.clamp(0.0, 1.0), Color::from_rgba8(c[0], c[1], c[2], c[3])))
        .collect())
}

/// Builds a path from separate x / y coordinate arrays.
/// Returns None if a pointer is null or there are fewer than 2 points.
/// The caller must guarantee that both pointers are valid for n_points elements.
unsafe fn points_to_path(
    x_points: *const f64, y_points: *const f64, n_points: u32, close: bool) -> Option<BezPath> {
    if x_points.is_null() || y_points.is_null() || n_points < 2 {
        return None;
    }
    let n = n_points as usize;
    let xs = unsafe { slice::from_raw_parts(x_points, n) };
    let ys = unsafe { slice::from_raw_parts(y_points, n) };

    let mut path = BezPath::new();
    path.move_to((xs[0], ys[0]));
    for i in 1..n {
        path.line_to((xs[i], ys[i]));
    }
    if close {
        // connect the last point back to the first one
        path.close_path();
    }
    Some(path)
}

/// Builds a single-line layout of the text with the current font settings.
fn build_layout(ctx: &RenderContext, text: &str) -> Layout<()> {
    // the shared text engine is locked only while the layout is built;
    // the finished layout owns its data and no longer borrows the contexts
    let mut layout: Layout<()> = {
        let mut guard = lock(&ctx.shared.text);
        let TextResource { font_cx, layout_cx } = &mut *guard;
        let mut builder = layout_cx.ranged_builder(font_cx, text, 1.0, true);
        builder.push_default(StyleProperty::FontSize(ctx.st.font_size));
        builder.push_default(StyleProperty::FontWeight(FontWeight::new(ctx.st.font_weight)));
        builder.push_default(StyleProperty::FontFamily(FontFamily::Source(
            Cow::Borrowed(ctx.st.font_family.as_str()),
        )));
        let layout = builder.build(text);
        layout
    };
    layout.break_all_lines(None); // no wrapping
    layout.align(Alignment::Start, AlignmentOptions::default());
    layout
}

/// Returns the width of the text laid out as a single line.
/// Trailing whitespace is included, same as the value fill_text() uses for its end point.
fn measure_width(ctx: &RenderContext, text: &str) -> f64 {
    if text.is_empty() { return 0.0; }
    build_layout(ctx, text).full_width() as f64
}

/// Registers font data in the shared collection and returns the number of faces added.
/// The family names are written to the optional output buffer.
fn register_font_bytes(data: Vec<u8>, name_buf: *mut u8, name_cap: usize) -> u32 {
    let shared = acquire_shared();
    let names: Vec<String>;
    let faces: u32;
    {
        // the font collection is shared, so it must be locked while it is modified
        let mut text = lock(&shared.text);
        let registered = text.font_cx.collection.register_fonts(Blob::from(data), None);
        faces = registered.iter().map(|(_, f)| f.len() as u32).sum();
        // copy the names out, so nothing borrows the collection after the lock is released
        names = registered
            .iter()
            .filter_map(|(id, _)| text.font_cx.collection.family_name(*id).map(str::to_string))
            .collect();
    }
    unsafe { write_c_string(&names.join("\n"), name_buf, name_cap); }
    faces
}

/// Copies a string into a caller-provided buffer as a NUL-terminated UTF-8 string.
/// The string is truncated at a character boundary if the buffer is too small.
/// Does nothing if the buffer is null or has no room.
/// The caller must guarantee that out_ptr is valid for out_cap bytes.
unsafe fn write_c_string(s: &str, out_ptr: *mut u8, out_cap: usize) {
    if out_ptr.is_null() || out_cap == 0 { return; }
    let mut n = s.len().min(out_cap - 1);
    while !s.is_char_boundary(n) { n -= 1; }
    let dst = unsafe { slice::from_raw_parts_mut(out_ptr, out_cap) };
    dst[..n].copy_from_slice(&s.as_bytes()[..n]);
    dst[n] = 0;
}

/// Lays out the text and draws its glyphs, filled or stroked.
/// The fill case uses the fill paint; the stroke case uses the stroke paint,
/// width, cap and join. The transform, alpha, blend mode, clip, text alignment
/// and text baseline apply.
/// If the layout is wider than max_width, it is scaled horizontally around the anchor.
/// Pass f64::INFINITY for no limit.
fn draw_text_internal(ctx_ptr: *mut RenderContext, text: &str, x: f64, y: f64,
                      stroke: bool, max_width: f64) -> Point {

    if ctx_ptr.is_null() { return Point { x, y }; }
    // rejects zero, negative and NaN (all comparisons with NaN are false)
    if !(max_width > 0.0) { return Point { x, y }; }
    let ctx = unsafe { &mut *ctx_ptr };

    let layout = build_layout(ctx, text);
    let Some(first_line) = layout.lines().next() else { return Point { x, y }; };
    let height = layout.height();

    // vertical anchor: top of the line box
    let origin_y = match ctx.st.text_baseline {
        1 => y as f32,                       // top
        2 => y as f32 - height / 2.0,        // center
        3 => y as f32 - height,              // bottom
        _ => y as f32 - first_line.metrics().baseline, // baseline
    };

    // horizontal scale to fit max_width; 1.0 when the text already fits
    let width = layout.full_width() as f64;
    let scale = if width > max_width { max_width / width } else { 1.0 };
    let shown = width * scale;

    // horizontal anchor: left end of the (scaled) text
    let x0 = match ctx.st.text_align {
        1 => x - shown / 2.0,
        2 => x - shown,
        _ => x,
    };

    // scale around (x0, y) so that the left end and the baseline do not move
    let transform = ctx.st.transform
        * Affine::translate((x0, y))
        * Affine::scale_non_uniform(scale, 1.0)
        * Affine::translate((-x0, -y));

    // hinting assumes unscaled, axis-aligned glyphs, so it is turned off
    // when the text is squeezed or the transform has scale / rotation
    let unscaled = scale == 1.0 && {
        let c = ctx.st.transform.as_coeffs();
        c[0] == 1.0 && c[1] == 0.0 && c[2] == 0.0 && c[3] == 1.0
    };

    // choose paint and style; the stroke is built before borrowing the scene mutably
    let stroke_style = ctx.stroke_style();
    let style: StyleRef = if stroke {
        StyleRef::from(&stroke_style)
    } else {
        StyleRef::from(Fill::NonZero)
    };
    let brush = ctx.brush(stroke);

    ctx.sync_clip();
    let pad = ctx.st.font_size as f64 * 0.25
        + if stroke { ctx.st.line_width * ctx.st.miter_limit.max(1.0) } else { 0.0 };
    let bounds = Rect::new(x0, origin_y as f64, x0 + shown, (origin_y + height) as f64)
        .inflate(pad, pad);
    let blended = ctx.push_blend(bounds);

    let scene = &mut ctx.scene;
    for line in layout.lines() {
        for item in line.items() {
            let PositionedLayoutItem::GlyphRun(glyph_run) = item else { continue; };
            let run = glyph_run.run();
            let baseline = origin_y + glyph_run.baseline();
            let mut cursor = x0 as f32 + glyph_run.offset();
            scene
                .draw_glyphs(run.font())
                .font_size(run.font_size())
                .transform(transform)
                .hint(unscaled)
                .normalized_coords(run.normalized_coords())
                .brush(&brush)
                .draw(style, glyph_run.glyphs().map(|g| {
                    // g.x / g.y are offsets inside the run; y points up in parley
                    let glyph = Glyph { id: g.id, x: cursor + g.x, y: baseline - g.y };
                    cursor += g.advance;
                    glyph
                }));
        }
    }
    if blended { ctx.scene.pop_layer(); }

    // bottom-right corner: full_width() includes trailing whitespace, so the caller
    // can continue drawing right after the text; the width is the scaled one
    Point {
        x: x0 + shown,
        y: (origin_y + height) as f64,
    }
}

/// Returns the byte length of one row. wgpu requires bytes_per_row of a
/// texture-to-buffer copy to be a multiple of 256, so the caller must pass a
/// width that satisfies this (width * 4 % 256 == 0, i.e. width % 64 == 0).
fn row_bytes(width: u32) -> u32 {
    let bytes = width * 4;
    assert_eq!(bytes % wgpu::COPY_BYTES_PER_ROW_ALIGNMENT, 0,
               "width ({width}) * 4 must be a multiple of {} bytes",
               wgpu::COPY_BYTES_PER_ROW_ALIGNMENT);
    bytes
}

/// Creates the render target texture and the readback buffer for the given size.
fn create_render_target(device: &wgpu::Device, width: u32, height: u32)
        -> (wgpu::Texture, wgpu::Buffer) {

    let row = row_bytes(width);

    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("Render Target Texture"),
        size: wgpu::Extent3d {
            width,
            height,
            depth_or_array_layers: 1,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT
             | wgpu::TextureUsages::COPY_SRC
             | wgpu::TextureUsages::STORAGE_BINDING,
        view_formats: &[],
    });

    let readback_buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("Readback Buffer"),
        // rows are tightly packed, so the size is exactly width * 4 * height
        size: row as wgpu::BufferAddress * height as wgpu::BufferAddress,
        usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });

    (texture, readback_buffer)
}
