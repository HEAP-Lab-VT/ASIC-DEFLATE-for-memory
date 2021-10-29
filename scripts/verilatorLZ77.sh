#!/usr/bin/env bash

# argument parsing from https://stackoverflow.com/a/14203146/8353152
traceargs=""
POSITIONAL=()
while [[ $# -gt 0 ]]; do
  key="$1"
  
  case $key in
    --trace)
      traceargs+=" --trace -CFLAGS \"-DTRACE_ENABLE=true\""
      shift # past argument
      ;;
    --no-trace)
      traceargs+=" -CFLAGS \"-DTRACE_ENABLE=false\""
      shift # past argument
      ;;
    --trace-underscore)
      traceargs+=" --trace-underscore"
      shift # past argument
      ;;
    *)    # unknown option
      POSITIONAL+=("$1") # save it in an array for later
      shift # past argument
      ;;
  esac
done
set -- "${POSITIONAL[@]}" # restore positional parameters


rm obj_dir/V*
verilator -Wno-WIDTH --cc LZ77Compressor.v --exe LZ77Compressor.cpp $traceargs && make -j12 -C obj_dir/ -f VLZ77Compressor.mk VLZ77Compressor
verilator -Wno-WIDTH --cc LZ77Decompressor.v --exe LZ77Decompressor.cpp $traceargs && make -j12 -C obj_dir/ -f VLZ77Decompressor.mk VLZ77Decompressor
