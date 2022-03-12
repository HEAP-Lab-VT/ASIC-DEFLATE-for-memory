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
    -j)
      if [[ "$2" =~ ^- ]]; then
        j="-j"
      else
        j="-j $2"
        shift
      fi
      shift
      ;;
    *)    # unknown option
      POSITIONAL+=("$1") # save it in an array for later
      shift # past argument
      ;;
  esac
done
set -- "${POSITIONAL[@]}" # restore positional parameters


rm obj_dir/V*
rm obj_dir/*.{o,d}
mod=LZ77Compressor
verilator -Wno-WIDTH --cc $mod.v --exe DecoupledStreamModule.cpp -CFLAGS "-D 'MODNAME=$mod' -D 'IN_CHARS=11' -D 'OUT_CHARS=8'" $traceargs &&
make $j -C obj_dir/ -f V$mod.mk V$mod

rm obj_dir/DecoupledStreamModule.{o,d}
mod=LZ77Decompressor
verilator -Wno-WIDTH --cc $mod.v --exe DecoupledStreamModule.cpp -CFLAGS "-D 'MODNAME=$mod' -D 'IN_CHARS=8' -D 'OUT_CHARS=8'" $traceargs &&
make $j -C obj_dir/ -f V$mod.mk V$mod
