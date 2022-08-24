
import java.io.File;
import java.util.Objects;
import javax.inject.Inject;
import org.gradle.api.*;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
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
  @Input @Optional
  abstract Property<Boolean> getTrace();
  @Internal
  abstract Property<Long> getSlurmJobId();
  
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
          params.getTrace().set(getTrace());
          params.getSlurmJobId().set(getSlurmJobId());
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
  abstract Property<Boolean> getTrace();
  abstract Property<Long> getSlurmJobId();
}

abstract class PTAction implements WorkAction<PTParams> {
  private final ExecOperations execOps;
  private final FileSystemOperations fsOps;
  
  @Inject
  public PTAction(ExecOperations execOps, FileSystemOperations fsOps) {
    this.execOps = execOps;
    this.fsOps = fsOps;
  }
  
  @Override
  public void execute() {
    PTParams params = this.getParameters();
    if(params.getUseSlurm().getOrElse(false)) {
      try {
        Thread.sleep(
          java.util.concurrent.ThreadLocalRandom.current().nextInt() % 60000);
      } catch(InterruptedException ex) {
        throw new RuntimeException(ex);
      }
    }
    ExecResult res = execOps.exec(e -> {
      if(params.getUseSlurm().getOrElse(false)) {
        e.executable("srun");
        e.args("--ntasks", "1");
        if(params.getSlurmJobId().isPresent()) {
          e.args("--jobid", params.getSlurmJobId().get().toString());
        } else {
          e.args("--time", "12:00:00"); // 12-hour time limit
          e.args("--job-name", "ASIC DEFLATE test");
        }
        e.args("--quiet");
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
      if(params.getTrace().getOrElse(false)) {
        e.args("--c-trace", params.getReport().get() + "_c.vcd");
        e.args("--d-trace", params.getReport().get() + "_d.vcd");
      }
      e.setIgnoreExitValue(true);
    });
    
    if(res.getExitValue() != 0) {
      System.err.println("Test failed: dump = " + params.getDump().get() +
        " : seek = " + params.getDumpSeek().get() +
        " : exit = " + (byte)res.getExitValue());
    } else {
      if(params.getTrace().getOrElse(false)) {
        fsOps.delete(s -> s.delete(params.getReport().get() + "_c.vcd"));
        fsOps.delete(s -> s.delete(params.getReport().get() + "_d.vcd"));
      }
    }
    res.assertNormalExitValue();
  }
}
