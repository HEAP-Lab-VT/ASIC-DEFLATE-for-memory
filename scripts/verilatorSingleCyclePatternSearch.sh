rm ./obj_dir/V*
rm ./obj_dir/*.[adoh]
verilator --cc wrappers/singleCyclePatternSearchWrapper.v singleCyclePatternSearch.v --exe singleCyclePatternSearchMain.cpp && make -j12 -C obj_dir/ -f VsingleCyclePatternSearchWrapper.mk VsingleCyclePatternSearchWrapper
