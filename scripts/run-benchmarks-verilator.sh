#!/bin/bash

benchmarks="mlps/mlp9"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
suffix="-baremetal"
build_dir="${script_dir}/../software/gemmini-rocc-tests/build"

help () {
    echo "Run Gemmini benchmarks on Verilator:"
    echo "  $benchmarks"
    echo
    echo "Usage: $0 [-h|--help] [-d|--dry-run]"
    echo
    echo "Options:"
    echo " -h | --help  Show this help message"
    echo
    echo " -d | --dry-run  Print the commands that would be run, but don't run them"
    echo
    echo " matmul_option  The matmul option to use. This can be either 'os' or 'ws'."
    exit
}

show_help=0
dry_run=0
matmul_option=ws

while [ $# -gt 0 ] ; do
  case $1 in
    -h | --help) show_help=1 ;;
    -d | --dry-run) dry_run=1 ;;
    *)
        matmul_option=$1
        if [ "$matmul_option" != "os" ] && [ "$matmul_option" != "ws" ]; then
            echo "Invalid matmul option: $matmul_option"
            exit 1
        fi
  esac

  shift
done

if [ $show_help -eq 1 ]; then
    help
fi

for benchmark in $benchmarks; do
    echo "Running ${benchmark}"
    full_binary_path="${build_dir}/${benchmark}${suffix}"
    if [ ! -f "${full_binary_path}" ]; then
        echo "Binary not found: $full_binary_path"
        exit 1
    fi
    cmd="${script_dir}/../../../sims/verilator/simulator-chipyard.harness-CustomGemminiSoCConfig $full_binary_path $matmul_option"
    if [ $dry_run -eq 1 ]; then
        echo $cmd
    else
        eval $cmd
    fi
done