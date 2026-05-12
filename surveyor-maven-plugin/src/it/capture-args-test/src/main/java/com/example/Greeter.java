package com.example;

public class Greeter {
    public String greet(String name, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append("Hello, ").append(name).append("!");
        }
        return sb.toString();
    }
}
