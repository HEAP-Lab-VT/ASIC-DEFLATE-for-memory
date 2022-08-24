#!/usr/bin/env bash

# argument parsing from https://stackoverflow.com/a/14203146/8353152
traceargs=""
j=""
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


er() {
  echo "$@"
  $@
}

amod=DeflateCompressor
verilator -Wno-WIDTH --cc $amod.v $traceargs &&
make $j -C obj_dir/ -f V$amod.mk V${amod}__ALL.a

bmod=DeflateDecompressor
verilator -Wno-WIDTH --cc $bmod.v --exe V${amod}__ALL.a ../verilator/Deflate_fused.cpp ../verilator/BitQueue.c $traceargs &&
make $j -C obj_dir/ -f V$bmod.mk V${bmod} &&
cp obj_dir/VDeflateDecompressor obj_dir/VDeflate_fused

# make -C obj_dir -f ../verilator/Huffman_fused.mk

#er g++ -c -I. -I${VERILATOR_ROOT}/include ../verilator/Huffman_fused.cpp -o VHuffman_fused.o
#er g++ Huffman_fused.o V${amod}__ALL.a V${bmod}__ALL.a -o VHuffman_fused
