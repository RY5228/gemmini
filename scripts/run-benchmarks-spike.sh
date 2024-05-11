#!/bin/bash

benchmarks="mobilenet resnet50 transformer"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

help () {
    echo "Run Gemmini benchmarks on Spike:"
    echo "  $benchmarks"
    echo
    echo "Usage: $0 [-h|--help] [-d|--dry-run]"
    echo
    echo "Options:"
    echo " -h | --help  Show this help message"
    echo
    echo " -d | --dry-run  Print the commands that would be run, but don't run them"
    exit
}

show_help=0
dry_run=0

while [ $# -gt 0 ] ; do
  case $1 in
    -h | --help) show_help=1 ;;
    -d | --dry-run) dry_run=1 ;;
  esac

  shift
done

if [ $show_help -eq 1 ]; then
    help
fi

for benchmark in $benchmarks; do
    echo "Running $benchmark"
    if [ $dry_run -eq 1 ]; then
        echo $script_dir/run-spike.sh $benchmark
    else
        $script_dir/run-spike.sh $benchmark
    fi
done