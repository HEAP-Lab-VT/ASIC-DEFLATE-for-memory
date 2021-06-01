rm ./obj_dir/V*
rm ./obj_dir/*.[adoh]
verilator -Wno-WIDTH --cc wrappers/lz77CompressorDecompressorWrapper.v lz77CompressorDecompressor.v --exe lz77Simulation.cpp && make -j12 -C obj_dir/ -f Vlz77CompressorDecompressorWrapper.mk Vlz77CompressorDecompressorWrapper
