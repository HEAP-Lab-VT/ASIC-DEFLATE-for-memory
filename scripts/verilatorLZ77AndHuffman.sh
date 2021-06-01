rm ./obj_dir/V*
rm ./obj_dir/*.[adoh]
verilator -Wno-WIDTH --cc wrappers/lz77AndHuffmanWrapper.v lz77AndHuffman.v --exe lz77AndHuffmanSimulation.cpp && make -j12 -C obj_dir/ -f Vlz77AndHuffmanWrapper.mk Vlz77AndHuffmanWrapper
