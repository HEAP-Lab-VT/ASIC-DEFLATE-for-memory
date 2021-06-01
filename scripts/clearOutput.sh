rm *.v *.fir *.anno.json *.log wiresFile.txt 
rm testbenches/*.fst
rm testbenches/*.vcd
rm testbenches/*.vvp
rm ./obj_dir/V*
rm ./obj_dir/*.[adoh]
rm .treadle*
cd rust
for file in $(ls); do cd $file; cargo clean; cd ../; done
cd ../