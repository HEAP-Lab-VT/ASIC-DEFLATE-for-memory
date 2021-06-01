# Hardware Compression and Decompression Accelerators
This repository contains a variety of hardware compressors and decompressors and the scripts necessary to build, simulate, debug, and test them.

## Requirements
### Mandatory 
sbt (Scala is required for Chisel3, the language most of the hardware is written in): https://www.scala-sbt.org/download.html


### Optional
Python3 (Python3 is used for miscellaneous scripting): https://www.python.org/downloads/
Rust (Rust is used for some scripts as well): https://www.rust-lang.org/tools/install
Verilator (Verilator is used to create C++ models of hardware for fast simulation): https://www.veripool.org/projects/verilator/wiki/Installing
Icarus Verilog (Icarus Verilog is used to quickly simulate hardware and generate traces for debugging): http://iverilog.icarus.com/
GTKWave (GTKWave is used to view waveforms for debugging): http://gtkwave.sourceforge.net/

## Setup
Once the software requirements are installed, the scripts in the ```scripts/``` directory can be run from the root of the git repo directory to generate the Verilog for a given design. Each design that can generate Verilog has a matching script in ```scripts/``` of the same name. If memory issues occur, changing the value of the ```-mem``` argument in the scripts can fix this problem. Increase the value if there is not enough memory and garbage collecting is spending too much time, and decrease the value if the value exceeds the total memory of the system.

 ```scripts/``` also includes other utilities for convenience. Scripts that start with "verilator" generate the Verilog for a design and compile the design with Verilator to make an executable compressor and decompressor combo. This can be used for checking correctness of compression or for checking compression ratios of the hardware.

***TODO:*** add instructions for compiling Verilator and running Icarus Verilog testbenches

## Troubleshooting
If for some reason the repo is not recognizing any Chisel classes or types, run "sbt update" in the from the root of the project. This will update SBT's currently downloaded dependencies. This has fixed the issue in the past.

## Resources
If you need help with Chisel, great places to look are the Chisel Cheat Sheet (https://inst.eecs.berkeley.edu/~cs250/sp17/handouts/chisel-cheatsheet3.pdf) and the Chisel Cookbook (https://www.chisel-lang.org/chisel3/docs/cookbooks/cookbook.html)