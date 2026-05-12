package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class GreeterTest {

    @Test
    public void testGreet() {
        Greeter g = new Greeter();
        assertEquals("Hello, World!Hello, World!", g.greet("World", 2));
    }
}
