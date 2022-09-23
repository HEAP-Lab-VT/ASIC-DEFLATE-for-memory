# ASIC Deflate for Memory

This is an ASIC Deflate compression/decompression accelerator designed
specifically for memory compression. We present the design in our paper
"Translation-Optimized Memory Compression for Capacity".

Note: We call it "Deflate" only because it uses a serial progression of LZ and
Huffman, which is the core concept of Deflate. However, our design is not
[RFC 1951](https://datatracker.ietf.org/doc/html/rfc1951) compliant.

## Dependencies
- Java 8 or later
- [Gradle](https://gradle.org/install/) 7.5 (optional, may use wrapper script)
- Scala 2.13 (downloaded automatically)
- Chisel 3.5 (downloaded automatically)
- [Verilator](https://verilator.org/guide/latest/install.html)
  (for RTL simulation testing)
- Make (for building test executables)
- g++ (for building test executables)

## A brief note on using Gradle

Gradle may be run by executing `gradle <task name>...` on the command line
when in the project directory. If the correct version of Gradle is not installed
on the system, use `./gradlew` or `./gradlew.bat` (Windows) instead of `gradle`.
A list of all available tasks can be listed by running `gradle tasks`.

Gradle resolves task dependencies automatically, so you don't need to manually
run dependencies. For example, if you run the `runTestDeflate` task, Gradle will
automatically run `genDeflateCompressor`, `genDeflateDecompressor` and
`buildTestDeflate` before running `runTestDeflate`. The only notable exception
to this is the `reportTestDeflate` task which does not automatically run
`runTestDeflate`.

## Verilog generation

Use the following Gradle tasks for generating Verilog for the design:
- `genDeflate` - Generate Deflate Verilog for both compressor and decompressor
- `genDeflateCompressor` - Generate Deflate compressor Verilog
- `genDeflateDecompressor` - Generate Deflate decompressor Verilog
- `genHuffman` - Generate Huffman Verilog for both compressor and decompressor
- `genHuffmanCompressor` - Generate Huffman compressor Verilog
- `genHuffmanDecompressor` - Generate Huffman decompressor Verilog
- `genLZ` - Generate LZ Verilog for both compressor and decompressor
- `genLZCompressor` - Generate LZ compressor Verilog
- `genLZDecompressor` - Generate LZ decompressor Verilog

Generated Verilog will appear in the `build` directory with the `.v` file
extension.

## Testing

Before running tests, put at least one benchmark file in the `testBenchmarks`
directory. Any file will do, but to appropriately measure performance for memory
compression, we recommend using memory dumps from a variety of programs with a
substantial memory footprint. The files will automatically be split into 4KiB
pages, and pages of all zeros will be dropped. Performance results such as
compression ratio and throughput will vary depending on the benchmarks used. If
the directory is empty, no tests will be run.

Note: Currently, LZ and Huffman may not be tested individually with Gradle
tasks.

Use the following Gradle tasks for testing
- `buildTestDeflate` - Build test executable for Deflate
- `runTestDeflate` - Run Deflate test
- `reportTestDeflate` - Report the results of the previous Deflate test
- `cleanTest` - Delete test executables and results

Raw test results will appear in the `/build/test/deflate-reports-frag`
directory. Summarized results will appear in the `/build/test/deflate-reports`
directory with one file per benchmark.

By default, testing will run in parallel on all CPU cores. This may be changed
with the `--max-workers <num threads>` command line option.

## Customizing Parameters

Configuration files may be found in the `configFiles` directory, and these may
be modified per your specific needs. Changing parameters can drastically affect
the time and resources required to generate the Verilog and synthesize it.

## Synthesis

We use Synopsys Design Compiler Ultra to synthesize the design for a 7nm ASAP
technology node at 2.5 GHz. Following is a sample TCL script for synthesizing
the LZ decompressor:

```
set_host_options -max_cores 12
set high_fanout_net_threshold 0
set target_library {"./libs/7nmLibs/7nm1.db" "./libs/7nmLibs/7nm2.db" "./libs/7nmLibs/7nm3.db" "./libs/7nmLibs/7nm4.db" "./libs/7nmLibs/7nm5.db" "./libs/7nmLibs/7nm6.db" "./libs/7nmLibs/7nm7.db" "./libs/7nmLibs/7nm8.db" "./libs/7nmLibs/7nm9.db" "./libs/7nmLibs/7nm10.db" "./libs/7nmLibs/7nm11.db" "./libs/7nmLibs/7nm12.db" "./libs/7nmLibs/7nm13.db" "./libs/7nmLibs/7nm14.db" "./libs/7nmLibs/7nm15.db" "./libs/7nmLibs/7nm16.db" "./libs/7nmLibs/7nm17.db" "./libs/7nmLibs/7nm18.db" "./libs/7nmLibs/7nm19.db" "./libs/7nmLibs/7nm20.db"}
set link_library {"./libs/7nmLibs/7nm1.db" "./libs/7nmLibs/7nm2.db" "./libs/7nmLibs/7nm3.db" "./libs/7nmLibs/7nm4.db" "./libs/7nmLibs/7nm5.db" "./libs/7nmLibs/7nm6.db" "./libs/7nmLibs/7nm7.db" "./libs/7nmLibs/7nm8.db" "./libs/7nmLibs/7nm9.db" "./libs/7nmLibs/7nm10.db" "./libs/7nmLibs/7nm11.db" "./libs/7nmLibs/7nm12.db" "./libs/7nmLibs/7nm13.db" "./libs/7nmLibs/7nm14.db" "./libs/7nmLibs/7nm15.db" "./libs/7nmLibs/7nm16.db" "./libs/7nmLibs/7nm17.db" "./libs/7nmLibs/7nm18.db" "./libs/7nmLibs/7nm19.db" "./libs/7nmLibs/7nm20.db"}
set vsources {"./LZDecompressor.v"}
analyze -format verilog $vsources
elaborate LZDecompressor
create_clock -name "clock" -period 400 "clock"
compile_ultra -retime
write_file -format ddc -output "LZDecompressor.ddc"
report_timing
report_area
report_power
```

Other modules, e.g. the Huffman decompressor, may be synthesized in a similar
manner.

<!--
Due to resource limitations, we are not able to synthesize some of the larger
modules, so we estimate results by individually synthesizing smaller chunks. We
do this for the Deflate compressor, Deflate decompressor, and Huffman
compressor modules.
-->
