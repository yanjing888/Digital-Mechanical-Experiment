package cn.ncut.lab.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FractureSummaryTest {
    @Test
    void tensileDuctileCurveAfterMaxLoad() {
        var points = List.of(
                Map.of("f", 10.0, "d", 0.5),
                Map.of("f", 20.0, "d", 1.0),
                Map.of("f", 30.0, "d", 1.5),
                Map.of("f", 30.0, "d", 2.0),
                Map.of("f", 25.0, "d", 2.8)
        );
        Map<String, Object> curve = FractureSummary.fromCurve(Map.of("points", points, "maxF", 30, "maxD", 2.8), "TENS");
        assertEquals("ductile", curve.get("judgment"));
    }

    @Test
    void tensileBrittleSuddenDrop() {
        var points = List.of(
                Map.of("f", 10.0, "d", 0.2),
                Map.of("f", 25.0, "d", 0.4),
                Map.of("f", 30.0, "d", 0.45),
                Map.of("f", 5.0, "d", 0.451),
                Map.of("f", 0.0, "d", 0.452)
        );
        Map<String, Object> curve = FractureSummary.fromCurve(Map.of("points", points, "maxF", 30, "maxD", 0.47), "TENS");
        assertEquals("brittle", curve.get("judgment"));
    }

    @Test
    void combineAgreesWhenBothDuctile() {
        Map<String, Object> summary = FractureSummary.build("TENS", Map.of(), List.of(
                Map.of("result", Map.of("candidate", "ductile", "evidence", List.of("a")))
        ));
        assertEquals("ductile", summary.get("judgment"));
    }
}
