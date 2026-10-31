export {usefulResult} from './driver.mjs';
export function fixtureLayout(resource=false,newAccounts=1000){
  if(!resource)return Array(200).fill('legacy');
  if(!Number.isSafeInteger(newAccounts)||newAccounts<1||newAccounts>10000)throw Error('Invalid fresh cohort size');
  return ['probe',...Array(180).fill('claimed'),...Array(10).fill('guest'),...Array(10).fill('active'),...Array(newAccounts).fill('new')];
}
export function workloadTask(kind,index,manifest){
  if(!['claimed','new','guest','active','progress','exams','mobile'].includes(kind)||!Number.isSafeInteger(index)||index<0)throw Error('Invalid workload');
  if(kind==='mobile'){
    const account=manifest.cohorts?.claimed?.[0];if(!account)throw Error('Mobile flow requires a claimed account');
    const tasks=[
      {method:'GET',path:'/v1/auth/providers'},
      {method:'GET',path:'/v1/account',token:account},
      {method:'GET',path:'/v1/minutes',token:account},
      {method:'POST',path:'/v1/progress',token:account,body:{entries:[{kind:'lesson',itemID:'en-foundations-u1-to-be',language:'en',at:1793318400000,attempts:1,bestScore:80}]}},
      {method:'GET',path:'/v1/progress',token:account},
      {method:'GET',path:'/v1/level-tests/hybrid?language=en&level=A1'},
    ];
    return tasks[index%tasks.length];
  }
  if(kind==='exams')return {method:'GET',path:'/v1/level-tests/hybrid?language=en&level=A1'};
  const cohort=manifest.cohorts?.[kind==='progress'?'claimed':kind];
  if(!cohort?.length || kind==='new'&&index>=cohort.length)throw Error('Fixture cohort exhausted or missing');
  const token=cohort[index%cohort.length];
  if(kind==='progress')return {method:'POST',path:'/v1/progress',token,body:{entries:[{
    kind:'lesson',itemID:'en-foundations-u1-to-be',language:'en',at:1793318400000,attempts:1,bestScore:80,
  }]}};
  return {method:'GET',path:'/v1/minutes',token};
}
