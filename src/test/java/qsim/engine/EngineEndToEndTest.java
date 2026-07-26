package qsim.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import jmt.engine.simDispatcher.DispatcherJSIMschema;
import org.junit.jupiter.api.Test;

class EngineEndToEndTest {

  @Test
  void solvesBareSimModelAndWritesSolutions() throws Exception {
    Path model = Files.createTempFile("mm1", ".xml");
    Files.copy(getClass().getResourceAsStream("/jmt/mm1.sim.xml"), model,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    DispatcherJSIMschema dispatcher = new DispatcherJSIMschema(model.toFile());
    dispatcher.setSimulationSeed(12345L);
    dispatcher.setTerminalSimulation(true);

    boolean ok = dispatcher.solveModel();
    assertTrue(ok, "solveModel() should return true");

    File out = dispatcher.getOutputFile();
    assertTrue(out != null && out.exists() && out.length() > 0,
        "engine should write a non-empty solutions file");
    String xml = Files.readString(out.toPath());
    assertTrue(xml.contains("<solutions") || xml.contains("<measure"),
        "output should be JMT solutions XML");
  }
}
