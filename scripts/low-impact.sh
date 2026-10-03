# Keeps a world or lobby from taking the whole machine; source it, then start the server through $LOW_IMPACT.
# Default: one JVM heap of at most 2 GB (a generated world loads its regions on demand), one processor as far as the
# JVM is concerned (so the cache packer, the collector and the region loaders each run on one thread), the lowest
# CPU and disk priority, and a single pinned core shared by everything that generates a world.
# GENERATION_MODE=fast: six pack threads inside a cgroup capped at 640% CPU (80% of 8 cores), for packing in minutes.
if [ "${GENERATION_MODE:-slow}" = fast ]; then
  export CACHE_PACK_THREADS=${CACHE_PACK_THREADS:-6}
  export CACHE_PACK_LOCK=${CACHE_PACK_LOCK:-/tmp/darkan-cache-pack-fast.lock}
  export JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx3g -XX:+UseParallelGC -XX:ParallelGCThreads=4}"
  LOW_IMPACT="systemd-run --user --scope --quiet -p CPUQuota=${GENERATION_CPU_QUOTA:-640}% nice -n 10"
else
  export JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx2g -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:CICompilerCount=2}"
  GENERATION_CPU=${GENERATION_CPU:-$(($(nproc) - 1))}
  LOW_IMPACT="nice -n 19 ionice -c 3 taskset -c $GENERATION_CPU"
fi
