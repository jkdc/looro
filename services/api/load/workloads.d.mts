export interface LoadTask {method:string;path:string;token?:string;body?:{entries:unknown[]}}
export function fixtureLayout(resource?:boolean,newAccounts?:number): string[];
export function workloadTask(kind:string,index:number,manifest:{tokens:string[];cohorts?:Record<string,string[]>}): LoadTask;
export function usefulResult(task:LoadTask,status:number,payload:unknown): boolean;
