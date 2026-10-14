package com.mammb.code.jfx.canvas.example;

import com.mammb.code.jfx.canvas.Canvas;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;


public class App extends Application {

    private static final int WIDTH = 400;
    private static final int HEIGHT = 300;

    @Override
    public void start(Stage stage) {

        var canvas = new Canvas(WIDTH, HEIGHT);
        canvas.setSize(500, 300);
        var fxCanvas = new javafx.scene.canvas.Canvas(WIDTH, HEIGHT);

        HBox hbox = new HBox();
        hbox.getChildren().addAll(fxCanvas, canvas);
        Scene scene = new Scene(hbox, WIDTH * 2, HEIGHT, Color.TRANSPARENT);
        stage.setScene(scene);
        stage.show();

        {
            var gc = fxCanvas.getGraphicsContext2D();
            gc.setFill(Color.BLACK);
            gc.fillRect(0, 0, WIDTH, HEIGHT);

            gc.setFill(Color.DARKBLUE);
            gc.fillRect(50, 50, 100, 100);

            gc.setFont(Font.font("Consolas", 14));
            gc.setFill(Color.WHITE);
            gc.fillText("Hello JavaFX Canvas フォント描画品質000", 100, 100);
        }

        {
            var gc = canvas.getGraphicsContext();
            gc.setFill(Color.BLACK);
            gc.fillRect(0, 0, WIDTH, HEIGHT);

            gc.setFill(Color.DARKBLUE);
            gc.fillRect(50, 50, 100, 100);

            gc.setFont(Font.font("Consolas", 14));
            gc.setFill(Color.WHITE);
            gc.fillText("Hello FFI Canvas フォント描画品質000", 100, 100);
        }

    }

}
