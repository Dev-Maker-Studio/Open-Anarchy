if [ "${GENERATION_MODE:-slow}" = fast ]; then
  export CACHE_PACK_THREADS=${CACHE_PACK_THREADS:-6}
  export CACHE_PACK_LOCK=${CACHE_PACK_LOCK:-/tmp/darkan-cache-pack-fast.lock}
  export JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx3g -XX:+UseParallelGC -XX:ParallelGCThreads=4}"
  LOW_IMPACT="systemd-run --user --scope --quiet -p CPUQuota=${GENERATION_CPU_QUOTA:-600}% nice -n 10"
else
  export JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx2g -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:CICompilerCount=2}"
  GENERATION_CPU=${GENERATION_CPU:-$(($(nproc) - 1))}
  LOW_IMPACT="nice -n 19 ionice -c 3 taskset -c $GENERATION_CPU"
fi
