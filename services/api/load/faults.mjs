import {assertOwnedContainer} from './resource-sampler.mjs';
export function faultAction(kind,owned){
  if(!/^palique-resource-[a-z0-9-]+$/.test(owned.project??''))throw Error('Synthetic project required');
  if(['slow','hang','http-error'].includes(kind))return {kind:'provider',mode:kind};
  const service={'restart-api':'api-load','restart-db':'database-load'}[kind];
  if(!service)throw Error('Unknown fixture fault');
  const container=owned.containers[service];
  assertOwnedContainer(container,owned.project,[service]);
  if(!/^[a-zA-Z0-9]+$/.test(container.Id))throw Error('Invalid container identity');
  return {kind:'restart',args:['restart','--time','5',container.Id]};
}
export function providerReply(mode,runId){
  if(!['fast','slow','hang','http-error'].includes(mode))throw Error('Unknown fixture provider mode');
  return {status:mode==='http-error'?429:200,delayMs:mode==='slow'?2000:0,hang:mode==='hang',body:mode==='http-error'?{error:{code:'synthetic_rate_limit'}}:{choices:[{message:{role:'assistant',content:runId}}],usage:{prompt_tokens:10,completion_tokens:5}}};
}
export const readyPayload=payload=>payload?.database===true;
