package com.example;

import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

public class CalculatorTest {

    private final Calculator calc = new Calculator();

    @Test
    public void testAdd() {
        assertEquals(calc.add(2, 3), 5);
    }

    @Test
    public void testMultiply() {
        assertEquals(calc.multiply(3, 4), 12);
    }
}
