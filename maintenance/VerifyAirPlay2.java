import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import wtf.nanoka.airplay.app.AirPlay2Audio;

/** Error containment and persisted switch test; does not claim audio playback. */
public class VerifyAirPlay2 {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("airplay2-java-check-");
        System.setProperty("user.home", home.toString());
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "airplay.airplay2.runtime", home.resolve("missing-runtime").toString())));
        var receiver = new AirPlay2Audio(environment);
        try {
            AirPlay2Audio.setEnabled(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!AirPlay2Audio.state().equals("error") && System.nanoTime() < deadline) Thread.sleep(25);
            if (!AirPlay2Audio.state().equals("error") || !AirPlay2Audio.detail().contains("runtime missing"))
                throw new AssertionError("Missing runtime must produce a contained failure");
            AirPlay2Audio.setEnabled(false);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!AirPlay2Audio.state().equals("disabled") && System.nanoTime() < deadline) Thread.sleep(25);
            if (AirPlay2Audio.enabled() || !AirPlay2Audio.state().equals("disabled"))
                throw new AssertionError("Disable must persist and stop the sidecar");
            System.out.println("AIRPLAY2_CONFIGURATION_AND_ERROR_CONTAINMENT_PASSED");
        } finally { receiver.destroy(); }
        if (!AirPlay2Audio.state().equals("stopped")) throw new AssertionError("Shutdown state");
    }
}
