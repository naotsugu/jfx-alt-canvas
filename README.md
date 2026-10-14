# jfx-alt-canvas

An alternative canvas component for JavaFX.

Powered by Rust's Vello on the backend,
it leverages GPU rendering and integrates seamlessly into JavaFX as an `ImageView`.

You can launch the sample application using the following command:

```shell
./gradlew run
```

> [!NOTE]
> Rust (Cargo) must be installed on your system.
> The Rust compilation process is fully automated via the Gradle build script pipeline.


## Usage

The API is identical to the standard JavaFX `Canvas`.

```java
var canvas = new Canvas(500, 300);
HBox hbox = new HBox();
hbox.getChildren().addAll(canvas);
Scene scene = new Scene(hbox, 500, 300, Color.TRANSPARENT);
stage.setScene(scene);
stage.show();
```

```java
var gc = canvas.getGraphicsContext2D();
gc.setFill(Color.BLACK);
gc.fillRect(0, 0, WIDTH, HEIGHT);

gc.setFill(Color.DARKBLUE);
gc.fillRect(50, 50, 100, 100);

gc.setFont(Font.font("Consolas", 14));
gc.setFill(Color.WHITE);
gc.fillText("Hello FFI Canvas フォント描画", 100, 100);
```

