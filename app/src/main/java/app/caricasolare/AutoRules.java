package app.caricasolare;

import java.util.Locale;

final class AutoRules {
    static final int DAY_PV_W = 100;
    static final int LEAN_SOC = 96;
    static final int LEAN_W = 300;
    static final int MAX_GRID_IMPORT_W = 3000;
    static final int MIN_A = 5;
    static final int REARM_SOC = 90;
    static final int RESERVE_W = 100;
    static final int START_CYCLES = 2;
    static final int STOP_CYCLES = 3;
    static final int SUPPORT_SOC = 50;
    static final int SUPPORT_W = 1000;
    static final int TAPER_SOC = 95;
    static final int VOLTS = 230;

    private AutoRules() {
    }

    static int endOfDay(double pvW, int hourOfDay, boolean sawDay, int darkCount, boolean startedByAuto) {
        if (pvW >= DAY_PV_W) {
            return 0;
        }
        if (!sawDay || hourOfDay < 14) {
            return darkCount;
        }
        int nextDark = darkCount + 1;
        if (nextDark < STOP_CYCLES || startedByAuto) {
            return nextDark;
        }
        return -1;
    }

    static boolean leanToCar(String pref, Solar solar, Memory memory) {
        if (solar.soc < LEAN_SOC) {
            return false;
        }
        return !"casa".equals(pref) || memory.homeFull;
    }

    static final class Solar {
        double pv;
        double grid;
        double bat;
        double soc;
        double batMax = 3000.0d;

        Solar(double pv, double grid, double bat, double soc) {
            this.pv = pv;
            this.grid = grid;
            this.bat = bat;
            this.soc = soc;
        }
    }

    static final class Car {
        boolean online = true;
        boolean plugged;
        boolean charging;
        int soc;
        int limit = 80;
        int amps;
        int requestAmps;
        int maxAmps = 16;
        double powerW;
        Boolean atHome;
    }

    static final class Memory {
        int startCount;
        int stopCount;
        boolean startedByAuto;
        boolean homeFull;

        void reset() {
            this.startCount = 0;
            this.stopCount = 0;
        }
    }

    static final class Decision {
        final String action;
        final int amps;
        final String text;

        Decision(String action, int amps, String text) {
            this.action = action;
            this.amps = amps;
            this.text = text;
        }

        static Decision none(String text) {
            return new Decision("none", 0, text);
        }

        @Override
        public String toString() {
            return this.action + (this.amps > 0 ? " " + this.amps + "A" : "") + " (" + this.text + ")";
        }
    }

    static double available(String pref, Solar solar, double carW) {
        int prioritySoc;
        if ("casa".equals(pref)) {
            prioritySoc = 100;
        } else if ("equilibrio".equals(pref)) {
            prioritySoc = 80;
        } else {
            prioritySoc = 0;
        }

        double contrib;
        if (solar.soc < prioritySoc) {
            boolean saturated = solar.soc >= TAPER_SOC || solar.grid > 200.0d;
            double want = saturated ? Math.max(solar.bat, 0.0d) : Math.max(solar.batMax, Math.max(solar.bat, 0.0d));
            contrib = solar.bat - want;
        } else if (solar.bat >= 0.0d) {
            contrib = solar.bat;
        } else {
            contrib = ("auto".equals(pref) && solar.soc >= SUPPORT_SOC)
                    ? Math.min(0.0d, solar.bat + SUPPORT_W)
                    : solar.bat;
        }
        return solar.grid + carW + contrib - RESERVE_W;
    }

    static double carPower(Car car) {
        if (car.charging) {
            return car.powerW > 0.0d ? car.powerW : car.amps * VOLTS;
        }
        return 0.0d;
    }

    static String kw(double w) {
        return Math.abs(w) >= 1000.0d
                ? String.format(Locale.ITALY, "%.1f kW", w / 1000.0d)
                : Math.round(w) + " W";
    }

    static Decision decide(String pref, Solar solar, Car car, Memory memory) {
        return decide(pref, solar, car, memory, false);
    }

    static Decision decide(String pref, Solar solar, Car car, Memory memory, boolean force) {
        if (car == null) {
            return Decision.none("Stato della Tesla non ancora letto");
        }
        if (!car.online) {
            return Decision.none("Tesla addormentata");
        }
        if (Boolean.FALSE.equals(car.atHome)) {
            memory.reset();
            return Decision.none("Tesla fuori casa: non tocco la carica");
        }
        if (!car.plugged) {
            memory.reset();
            memory.startedByAuto = false;
            return Decision.none("Tesla non collegata");
        }
        if (car.limit > 0 && car.soc >= car.limit) {
            memory.reset();
            memory.startedByAuto = false;
            return Decision.none("Tesla già al limite di carica (" + car.limit + "%)");
        }

        int maxAmps = car.maxAmps > 0 ? car.maxAmps : 16;
        double currentCarW = carPower(car);

        if ("max".equals(pref)) {
            memory.reset();
            if (car.charging) {
                return car.requestAmps != maxAmps
                        ? new Decision("amps", maxAmps, "Massima: porto a " + maxAmps + " A")
                        : Decision.none("Massima: in carica a " + maxAmps + " A");
            }
            return new Decision("start", maxAmps, "Massima: avvio a " + maxAmps + " A");
        }

        if (solar.pv < DAY_PV_W && car.charging && !memory.startedByAuto) {
            memory.reset();
            return Decision.none("Notte: la carica non l'ho avviata io, la lascio com'è");
        }

        if ("casa".equals(pref)) {
            updateHomeFull(solar, memory);
            if (!memory.homeFull) {
                memory.reset();
                long socRound = Math.round(solar.soc);
                String alSoc = ((String.valueOf(socRound).startsWith("8") || socRound == 11 || socRound == 18) ? "all'" : "al ") + socRound + "%";
                return car.charging
                        ? new Decision("stop", 0, "Casa prima: batteria di casa " + alSoc + ", fermo la Tesla finché non arriva al 95%")
                        : Decision.none("Casa prima: aspetto la batteria di casa al 95% (ora " + socRound + "%)");
            }
        }

        double availW = available(pref, solar, currentCarW);
        boolean lean = leanToCar(pref, solar, memory);
        if (lean) {
            availW += RESERVE_W + LEAN_W;
        }
        int rawAmps = (int) Math.floor(availW / (double) VOLTS);

        int targetAmps;
        String reason;
        if (rawAmps >= MIN_A) {
            memory.stopCount = 0;
            if (car.charging) {
                targetAmps = Math.min(rawAmps, maxAmps);
                reason = "sole libero " + kw(availW);
            } else {
                memory.startCount = force ? START_CYCLES : (memory.startCount + 1);
                if (memory.startCount < START_CYCLES) {
                    return Decision.none("Sole sufficiente (" + kw(availW) + "): confermo al prossimo controllo");
                }
                targetAmps = Math.min(rawAmps, maxAmps);
                reason = force ? ("sole libero " + kw(availW)) : ("sole libero " + kw(availW) + " confermato");
            }
        } else {
            memory.startCount = 0;
            if (!car.charging) {
                return Decision.none("Sole libero per la Tesla " + kw(Math.max(0.0d, availW)) + ": ne servono almeno " + kw(MIN_A * VOLTS));
            }
            memory.stopCount++;
            if (memory.stopCount < STOP_CYCLES) {
                reason = "sole in calo, tengo il minimo (" + memory.stopCount + "/" + STOP_CYCLES + ")";
                targetAmps = MIN_A;
            } else {
                reason = "sole insufficiente da " + STOP_CYCLES + " controlli";
                targetAmps = 0;
            }
        }

        int gridSafeAmps = (int) Math.floor((currentCarW + solar.grid + MAX_GRID_IMPORT_W) / (double) VOLTS);
        if (targetAmps > 0 && gridSafeAmps < targetAmps) {
            if (gridSafeAmps < MIN_A) {
                gridSafeAmps = 0;
            }
            reason = reason + ", limitato dal contatore";
            targetAmps = gridSafeAmps;
        }

        int finalAmps = Math.min(targetAmps, maxAmps);
        if (lean && finalAmps > 0) {
            reason = reason + " (batteria di casa quasi piena: arrotondo verso l'auto)";
        }

        if (finalAmps == 0) {
            return !car.charging ? Decision.none(cap1(reason)) : new Decision("stop", 0, "Fermo la carica: " + reason);
        }
        if (car.charging) {
            return Math.abs(finalAmps - car.requestAmps) >= 1
                    ? new Decision("amps", finalAmps, "Porto a " + finalAmps + " A: " + reason)
                    : Decision.none("In carica a " + finalAmps + " A: " + reason);
        }
        return new Decision("start", finalAmps, "Avvio a " + finalAmps + " A: " + reason);
    }

    static void updateHomeFull(Solar solar, Memory memory) {
        if (solar.soc >= TAPER_SOC) {
            memory.homeFull = true;
        } else if (solar.soc < REARM_SOC) {
            memory.homeFull = false;
        }
    }

    static Decision decideAsleep(String pref, Solar solar, Car car, Memory memory, boolean knownState) {
        return decideAsleep(pref, solar, car, memory, knownState, knownState, false);
    }

    static Decision decideAsleep(
            String pref,
            Solar solar,
            Car car,
            Memory memory,
            boolean knownPositive,
            boolean knownNegative,
            boolean force
    ) {
        if (car != null && knownNegative && Boolean.FALSE.equals(car.atHome)) {
            memory.reset();
            return Decision.none("Tesla addormentata fuori casa");
        }
        if (car != null && knownNegative && !car.plugged) {
            memory.reset();
            return Decision.none("Tesla addormentata e non collegata");
        }
        if (car != null && knownNegative && car.limit > 0 && car.soc >= car.limit) {
            memory.reset();
            return Decision.none("Tesla addormentata, già al limite");
        }

        if ("max".equals(pref)) {
            return new Decision("wake", 0, "Sveglio la Tesla per caricare alla massima corrente");
        }

        if (solar.pv < DAY_PV_W) {
            memory.reset();
            return Decision.none("Tesla addormentata, niente sole");
        }

        if ("casa".equals(pref)) {
            updateHomeFull(solar, memory);
            if (!memory.homeFull) {
                memory.reset();
                return Decision.none("Tesla addormentata. Casa prima: aspetto la batteria di casa al 95% (ora " + Math.round(solar.soc) + "%)");
            }
        }

        double availW = available(pref, solar, 0.0d);
        if (leanToCar(pref, solar, memory)) {
            availW += RESERVE_W + LEAN_W;
        }
        if ((int) Math.floor(availW / (double) VOLTS) < MIN_A) {
            memory.startCount = 0;
            return Decision.none("Tesla addormentata: sole libero " + kw(Math.max(0.0d, availW)) + ", ne servono " + kw(MIN_A * VOLTS) + " per svegliarla");
        }

        memory.startCount = force ? START_CYCLES : (memory.startCount + 1);
        if (memory.startCount < START_CYCLES) {
            return Decision.none("Tesla addormentata, sole libero " + kw(availW) + ": confermo al prossimo controllo");
        }

        String text = knownPositive
                ? "Sveglio la Tesla per caricare col sole (" + kw(availW) + " liberi)"
                : "Sveglio la Tesla per vedere se è collegata (" + kw(availW) + " di sole liberi)";
        return new Decision("wake", 0, text);
    }

    private static String cap1(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

