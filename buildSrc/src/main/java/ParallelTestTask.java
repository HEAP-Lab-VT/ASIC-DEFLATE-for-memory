
import java.io.File;
import java.util.Objects;
import javax.inject.Inject;
import org.gradle.api.*;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.workers.*;

abstract public class ParallelTestTask extends DefaultTask implements Task {
  private final WorkerExecutor executor;
  
  @Inject
  public ParallelTestTask(WorkerExecutor executor) {
    this.executor = executor;
  }
  
  @Input
  abstract Property<String> getExecutable();
  @InputFiles
  abstract Property<FileCollection> getDumps();
  @OutputDirectory
  abstract DirectoryProperty getReportDir();
  @Input
  abstract Property<Long> getChunkSize();
  @Internal
  abstract Property<Boolean> getUseSlurm();
  
  @TaskAction
  public void submitTests() {
    WorkQueue workQueue = executor.noIsolation();
    getDumps().get().filter(File::isFile).forEach(dump -> {
      DirectoryProperty reportDir =
        getProject().getObjects().directoryProperty().value(
          getReportDir().dir(dump.getName()));
      getProject().delete(reportDir);
      getProject().mkdir(reportDir);
      for(long seek = 0; seek < dump.length();
        seek += getChunkSize().getOrElse(Long.MAX_VALUE)
      ) {
        final long _seek = seek;
        workQueue.submit(PTAction.class, params -> {
          params.getExecutable().set(getExecutable());
          params.getDump().set(dump);
          params.getDumpSeek().set(_seek);
          params.getDumpLimit().set(getChunkSize());
          params.getReport().set(reportDir.file(dump.getName() + "_" + _seek));
          params.getUseSlurm().set(getUseSlurm());
        });
      }
    });
  }
}

abstract class PTParams implements WorkParameters {
  public PTParams() {}
  abstract Property<String> getExecutable();
  abstract RegularFileProperty getDump();
  abstract Property<Long> getDumpSeek();
  abstract Property<Long> getDumpLimit();
  abstract RegularFileProperty getReport();
  abstract Property<Boolean> getUseSlurm();
}

abstract class PTAction implements WorkAction<PTParams> {
  private final ExecOperations execOps;
  
  @Inject
  public PTAction(ExecOperations execOps) {
    this.execOps = execOps;
  }
  
  @Override
  public void execute() {
    PTParams params = this.getParameters();
    ExecResult res = execOps.exec(e -> {
      if(params.getUseSlurm().getOrElse(false)) {
        e.executable("srun");
        e.args("--ntasks", "1");
        e.args("--time", "12:00:00"); // 12-hour time limit
        e.args("--job-name",
          params.getExecutable().map(Objects::toString).getOrElse("") + ":" +
          params.getDump().map(Objects::toString).getOrElse("") + ":" +
          params.getDumpSeek().map(Objects::toString).getOrElse(""));
        e.args(params.getExecutable().get());
      } else {
        e.executable(params.getExecutable().get());
      }
      e.args("--dump", params.getDump().get());
      if(params.getDumpSeek().isPresent())
        e.args("--dump-seek", params.getDumpSeek().get());
      if(params.getDumpLimit().isPresent())
        e.args("--dump-limit", params.getDumpLimit().get());
      e.args("--report", params.getReport().get());
      e.setIgnoreExitValue(true);
    });
    
    if(res.getExitValue() != 0) {
      System.err.println("Test failed: dump = " + params.getDump().get() + " : seek = " +
        params.getDumpSeek().get() + " : exit = " + (byte)res.getExitValue());
    }
    res.assertNormalExitValue();
  }
}
