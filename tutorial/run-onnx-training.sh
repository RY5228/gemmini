#!/bin/bash

cd ./software/onnxruntime-riscv/
cd ./systolic_runner/imagenet_trainer/

spike --extension=gemmini pk resnet_train --model_name resnet50.onnx  --train_data_txt batch_out.txt --num_train_steps 1 --train_batch_size 2 -x 10 -d 0 -O 99