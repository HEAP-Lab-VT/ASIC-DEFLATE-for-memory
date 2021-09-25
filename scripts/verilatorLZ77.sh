rm obj_dir/V*
verilator -Wno-WIDTH --cc LZ77Compressor.v --exe LZ77Compressor.cpp $1 && make -j12 -C obj_dir/ -f VLZ77Compressor.mk VLZ77Compressor
verilator -Wno-WIDTH --cc LZ77Decompressor.v --exe LZ77Decompressor.cpp $1 && make -j12 -C obj_dir/ -f VLZ77Decompressor.mk VLZ77Decompressor
