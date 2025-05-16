Build:
```
../scripts/nvs_partition_gen.py generate board-config.csv  board-config.bin 0x6000
```


Flash:
```
esptool.py  write_flash 0x9000 board-config.bin
```