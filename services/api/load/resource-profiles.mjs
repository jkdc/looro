const MiB=1024**2;
const allocations={
  'api-load':[.40,1024],'database-load':[.35,1536],'caddy-load':[.05,128],
  'collector-load':[.06,256],'prometheus-load':[.07,512],'tempo-load':[.03,256],
  'alertmanager-load':[.02,64],'webhook-load':[.02,64],
};
export function resourceProfile(name){
  const choices={economy:[1,1],standard:[2,2],peak:[4,3]};
  if(!Object.hasOwn(choices,name))throw Error('Unknown resource profile');
  const factors=choices[name];
  return {name,cpuLimit:factors[0],memoryLimitBytes:4096*MiB*factors[1],services:Object.fromEntries(
    Object.entries(allocations).map(([service,[cpu,mem]])=>[service,{cpus:cpu*factors[0],memoryBytes:mem*MiB*factors[1]}]))};
}
export function validateResourceBudget(profile,activeServices){
  const expected=Object.keys(allocations).sort(),active=[...activeServices].sort();
  if(JSON.stringify(expected)!==JSON.stringify(active)||JSON.stringify(Object.keys(profile.services).sort())!==JSON.stringify(expected))throw Error('Incomplete resident service inventory');
  const values=Object.values(profile.services);
  if(values.some(v=>!Number.isFinite(v.cpus)||v.cpus<=0||!Number.isSafeInteger(v.memoryBytes)||v.memoryBytes<=0))throw Error('Invalid service budget');
  if(values.reduce((s,v)=>s+v.cpus,0)>profile.cpuLimit+1e-9||values.reduce((s,v)=>s+v.memoryBytes,0)>profile.memoryLimitBytes)throw Error('Aggregate resource budget exceeded');
  return true;
}
export function resourceEnvironment(profile){
  validateResourceBudget(profile,Object.keys(profile.services));
  return Object.fromEntries(Object.entries(profile.services).flatMap(([name,v])=>{
    const key=`LOAD_${name.replace('-load','').toUpperCase()}`;
    return [[`${key}_CPUS`,String(v.cpus)],[`${key}_MEMORY`,String(v.memoryBytes)]];
  }));
}
