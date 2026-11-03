package app.caricasolare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoRulesTest {

    private static AutoRules.Car pluggedCar(boolean charging, int amps, int soc) {
        AutoRules.Car car = new AutoRules.Car();
        car.online = true;
        car.plugged = true;
        car.charging = charging;
        car.amps = charging ? amps : 0;
        car.requestAmps = amps;
        car.maxAmps = 16;
        car.soc = soc;
        car.limit = 80;
        car.powerW = charging ? amps * AutoRules.VOLTS : 0.0d;
        car.atHome = Boolean.TRUE;
        return car;
    }

    @Test
    public void casaModeWaitsUntil95PercentThenSwitchesToCarWithHysteresis() {
        AutoRules.Memory mem = new AutoRules.Memory();
        AutoRules.Car car = pluggedCar(false, 8, 50);

        // Batteria di casa all'88%: Casa prima tiene ferma la Tesla
        AutoRules.Solar s88 = new AutoRules.Solar(3500, 100, 3000, 88);
        AutoRules.Decision d1 = AutoRules.decide("casa", s88, car, mem);
        assertEquals("none", d1.action);
        assertFalse(mem.homeFull);
        assertTrue(d1.text.contains("95%"));

        // Batteria di casa arriva al 95% e rallenta: 2200 W vanno verso la rete, 600 W in batteria
        AutoRules.Solar s95 = new AutoRules.Solar(3200, 2200, 600, 95);
        AutoRules.Decision d2 = AutoRules.decide("casa", s95, car, mem);
        assertTrue(mem.homeFull);
        assertEquals("none", d2.action); // ciclo 1/2 di conferma
        assertEquals(1, mem.startCount);

        AutoRules.Decision d3 = AutoRules.decide("casa", s95, car, mem);
        assertEquals("start", d3.action);
        // avail = 2200 (grid) + 0 (car) + 0 (saturated bat-want) - 100 (reserve) = 2100 W -> 9 A
        assertEquals(9, d3.amps);

        // Isteresi: se la batteria scende al 92% resta homeFull = true, si riarma solo sotto il 90%
        car.charging = true;
        car.amps = 9;
        car.requestAmps = 9;
        car.powerW = 9 * 230;
        AutoRules.Solar s92 = new AutoRules.Solar(2800, 100, 200, 92);
        AutoRules.Decision d4 = AutoRules.decide("casa", s92, car, mem);
        assertTrue(mem.homeFull);
        assertFalse("stop".equals(d4.action));

        AutoRules.Solar s89 = new AutoRules.Solar(2800, 100, 200, 89);
        AutoRules.Decision d5 = AutoRules.decide("casa", s89, car, mem);
        assertFalse(mem.homeFull);
        assertEquals("stop", d5.action);
    }

    @Test
    public void equilibrioModePrioritizesHomeBatteryBelow80ThenFeedsCar() {
        AutoRules.Memory mem = new AutoRules.Memory();
        AutoRules.Car car = pluggedCar(false, 6, 55);

        // Batteria al 65%: tutta la produzione (2600 W) carica la batteria (max 3000 W), zero per l'auto
        AutoRules.Solar s65 = new AutoRules.Solar(3000, 0, 2600, 65);
        AutoRules.Decision d1 = AutoRules.decide("equilibrio", s65, car, mem);
        assertEquals("none", d1.action);
        assertEquals(0, mem.startCount);

        // Batteria arriva all'81%: i 2600 W in batteria + 200 W in rete diventano disponibili per l'auto
        AutoRules.Solar s81 = new AutoRules.Solar(3200, 200, 2600, 81);
        AutoRules.decide("equilibrio", s81, car, mem);
        AutoRules.Decision d2 = AutoRules.decide("equilibrio", s81, car, mem);
        assertEquals("start", d2.action);
        // avail = 200 + 0 + 2600 - 100 = 2700 W -> 11 A
        assertEquals(11, d2.amps);
    }

    @Test
    public void decideAsleepWakesWhenHouseBatteryReadyAndSupportsMaxAndForce() {
        AutoRules.Memory mem = new AutoRules.Memory();
        AutoRules.Car sleeping = pluggedCar(false, 10, 50);
        sleeping.online = false;

        // Casa prima sotto il 95%: non sveglia
        AutoRules.Solar s70 = new AutoRules.Solar(3500, 500, 2500, 70);
        assertEquals("none", AutoRules.decideAsleep("casa", s70, sleeping, mem, true, true, false).action);

        // Casa prima al 96%: 1° ciclo conferma, 2° ciclo sveglia
        AutoRules.Solar s96 = new AutoRules.Solar(3500, 2400, 400, 96);
        assertEquals("none", AutoRules.decideAsleep("casa", s96, sleeping, mem, true, true, false).action);
        assertEquals("wake", AutoRules.decideAsleep("casa", s96, sleeping, mem, true, true, false).action);

        // Force = true sveglia subito al 1° ciclo
        mem.reset();
        assertEquals("wake", AutoRules.decideAsleep("auto", s70, sleeping, mem, true, true, true).action);

        // Massima sveglia anche di notte senza sole
        mem.reset();
        AutoRules.Solar night = new AutoRules.Solar(0, -400, 0, 40);
        assertEquals("wake", AutoRules.decideAsleep("max", night, sleeping, mem, true, true, false).action);
    }

    @Test
    public void stopDebounceAndGridImportLimitWork() {
        AutoRules.Memory mem = new AutoRules.Memory();
        mem.startedByAuto = true;
        AutoRules.Car car = pluggedCar(true, 10, 60);

        // Nuvola: sole scende sotto 5 A -> tiene 5 A per 2 cicli, ferma al 3°
        AutoRules.Solar cloud = new AutoRules.Solar(500, -1800, -200, 85);
        AutoRules.Decision c1 = AutoRules.decide("equilibrio", cloud, car, mem);
        assertEquals("amps", c1.action);
        assertEquals(5, c1.amps);

        car.amps = 5;
        car.requestAmps = 5;
        car.powerW = 5 * 230;
        AutoRules.Solar cloud2 = new AutoRules.Solar(500, -650, -200, 85);
        AutoRules.Decision c2 = AutoRules.decide("equilibrio", cloud2, car, mem);
        assertEquals("none", c2.action); // già a 5 A al controllo 2/3

        AutoRules.Decision c3 = AutoRules.decide("equilibrio", cloud2, car, mem);
        assertEquals("stop", c3.action);

        // Limite contatore: se la casa sta già prelevando 2200 W dalla rete, limita gli ampere
        AutoRules.Car idleCar = pluggedCar(false, 16, 40);
        AutoRules.Solar heavyLoad = new AutoRules.Solar(4000, -2200, 3500, 90);
        // avail sarebbe 3500 - 2200 - 100 = 1200 W (5 A), ma gridSafe = floor((0 - 2200 + 3000)/230) = 3 A < MIN_A -> 0 A
        AutoRules.Decision g1 = AutoRules.decide("equilibrio", heavyLoad, idleCar, mem, true);
        assertEquals("none", g1.action);
        assertTrue(g1.text.contains("limitato dal contatore"));
    }

    @Test
    public void endOfDayDetectsSunsetAfterDaylight() {
        assertEquals(0, AutoRules.endOfDay(450, 18, true, 2, false));
        assertEquals(0, AutoRules.endOfDay(20, 11, true, 0, false));
        assertEquals(1, AutoRules.endOfDay(20, 19, true, 0, false));
        assertEquals(2, AutoRules.endOfDay(20, 19, true, 1, false));
        assertEquals(-1, AutoRules.endOfDay(20, 19, true, 2, false));
        // Se la carica automatica sta ancora girando (es. gli ultimi cicli prima dello stop), aspetta che si fermi
        assertEquals(3, AutoRules.endOfDay(20, 19, true, 2, true));
    }
}

