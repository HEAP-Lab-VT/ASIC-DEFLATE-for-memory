rm ./obj_dir/V*
rm ./obj_dir/*.[adoh]
verilator --trace --trace-max-width 400 --trace-max-array 819 --trace-structs --cc wrappers/lzwCompressorDecompressorWrapper.v lzwCompressorDecompressor.v --exe lzwSimulationMain.cpp && make -j12 -C obj_dir/ -f VlzwCompressorDecompressorWrapper.mk VlzwCompressorDecompressorWrapper
