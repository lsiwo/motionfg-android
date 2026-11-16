package dev.lsiwo.motionfg;

import static android.opengl.GLES30.*;

import android.content.res.AssetManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class GlProgram {
    private final int program;
    private final Map<String, Integer> locations = new HashMap<>();

    GlProgram(AssetManager assets, int vertex, String colorFunctions, String fragmentFile) throws IOException {
        String fragment = load(assets, fragmentFile);
        if (fragment.contains("/* COLOR_FUNCTIONS */")) {
            fragment = fragment.replace("/* COLOR_FUNCTIONS */", colorFunctions);
        }
        int frag = compile(GL_FRAGMENT_SHADER, fragment, fragmentFile);
        program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, frag);
        glLinkProgram(program);
        glDetachShader(program, vertex);
        glDetachShader(program, frag);
        glDeleteShader(frag);
        int[] linked = new int[1];
        glGetProgramiv(program, GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException(fragmentFile + ": " + log);
        }
    }

    static String load(AssetManager assets, String file) throws IOException {
        try (InputStream input = assets.open("shaders/" + file)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static int compile(int type, String source, String name) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        int[] compiled = new int[1];
        glGetShaderiv(shader, GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException(name + ": " + log);
        }
        return shader;
    }

    void use() {
        glUseProgram(program);
    }

    int location(String name) {
        return locations.computeIfAbsent(name, key -> glGetUniformLocation(program, key));
    }

    void texture(String name, int unit, int target, int id) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(target, id);
        glUniform1i(location(name), unit);
    }

    void integer(String name, int value) { glUniform1i(location(name), value); }
    void scalar(String name, float value) { glUniform1f(location(name), value); }
    void vector(String name, float x, float y) { glUniform2f(location(name), x, y); }
}
