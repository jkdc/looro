function bytes(value){
  const m=String(value??'').trim().match(/^([\d.]+)\s*(B|kB|MB|GB|KiB|MiB|GiB)$/);
  const scale={B:1,kB:1000,MB:1e6,GB:1e9,KiB:1024,MiB:1024**2,GiB:1024**3};
  return m?Number(m[1])*scale[m[2]]:null;
}
function pair(value){return value?String(value).split('/').map(bytes):[null,null];}
export function assertOwnedContainer(inspect,project,services){
  const labels=inspect?.Config?.Labels;
  if(!project.startsWith('palique-resource-')||labels?.['com.docker.compose.project']!==project||!services.includes(labels?.['com.docker.compose.service']))throw Error('Container is not owned by resource experiment');
  return true;
}
export function assertEffectiveLimits(inspect,expected){
  if(inspect?.HostConfig?.NanoCpus!==Math.round(expected.cpus*1e9)||inspect?.HostConfig?.Memory!==expected.memoryBytes)throw Error('Effective resource limits differ from requested budget');
  return true;
}
export function summarizeContainer(inspect,stats={},cgroup=null){
  const fields=Object.fromEntries(String(cgroup??'').trim().split('\n').map(l=>l.trim().split(/\s+/)));
  const numeric=key=>fields[key]!==undefined&&Number.isFinite(Number(fields[key]))?Number(fields[key]):null;
  const cpu=stats.CPUPerc?.match(/^([\d.]+)%$/);
  return {service:inspect.Config?.Labels?.['com.docker.compose.service']??null,
    cpus:inspect.HostConfig?.NanoCpus?inspect.HostConfig.NanoCpus/1e9:null,memoryLimitBytes:inspect.HostConfig?.Memory||null,
    cpuPercent:cpu?Number(cpu[1]):null,memoryUsedBytes:pair(stats.MemUsage)[0],networkBytes:pair(stats.NetIO),blockBytes:pair(stats.BlockIO),
    cpuUsageUsec:numeric('usage_usec'),throttledUsec:numeric('throttled_usec'),throttledPeriods:numeric('nr_throttled'),
    oomKilled:inspect.State?.OOMKilled??null,restarts:inspect.RestartCount??null};
}
/** Only inspect identities obtained from this unique Compose project. exec is injectable. */
export async function sampleContainers(project,containers,profile,exec){
  const result=[];
  for(const id of containers){
    const [inspect]=JSON.parse(await exec(['inspect',id]));
    const service=inspect.Config?.Labels?.['com.docker.compose.service'];
    assertOwnedContainer(inspect,project,[...Object.keys(profile.services),'driver-load','stub-load']);
    if(profile.services[service])assertEffectiveLimits(inspect,profile.services[service]);
    const stats=JSON.parse(await exec(['stats','--no-stream','--format','{{json .}}',id]));
    let cgroup=null;
    try{cgroup=await exec(['exec',id,'cat','/sys/fs/cgroup/cpu.stat']);}catch{ /* Distroless images may not contain cat; report unavailable, never zero. */ }
    result.push({...summarizeContainer(inspect,stats,cgroup),includedInBudget:Boolean(profile.services[service])});
  }
  return result;
}
