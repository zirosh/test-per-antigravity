package app.caricasolare;

final class History {
    static final long MAX_GAP_MS = 900_000L;

    History() {
    }

    static double[] interval(long tPrev, long tNow, double pvW, double gridW, Double carW) {
        long dtMs = tNow - tPrev;
        if (dtMs <= 0 || dtMs > MAX_GAP_MS) {
            return null;
        }
        double hours = dtMs / 3_600_000.0d;
        double gridImportW = Math.max(0.0d, -gridW);
        double gridExportW = Math.max(0.0d, gridW);
        double carFromGridW;
        double carFromSunW;
        if (carW == null || carW <= 0.0d) {
            carFromGridW = 0.0d;
            carFromSunW = 0.0d;
        } else {
            carFromGridW = Math.min(carW, gridImportW);
            carFromSunW = carW - carFromGridW;
        }
        return new double[]{
                (carFromSunW * hours) / 1000.0d,
                (carFromGridW * hours) / 1000.0d,
                (Math.max(0.0d, pvW) * hours) / 1000.0d,
                (gridExportW * hours) / 1000.0d,
                (gridImportW * hours) / 1000.0d
        };
    }
}

