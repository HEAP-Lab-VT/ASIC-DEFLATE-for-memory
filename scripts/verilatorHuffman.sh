rm ./obj_dir/V*
rm ./obj_dir/*.[adoh]
verilator --cc wrappers/huffmanCompressorDecompressorWrapper.v huffmanCompressorDecompressor.v --exe huffmanSimulationMain.cpp && make -j12 -C obj_dir/ -f VhuffmanCompressorDecompressorWrapper.mk VhuffmanCompressorDecompressorWrapper && time ./obj_dir/VhuffmanCompressorDecompressorWrapper
