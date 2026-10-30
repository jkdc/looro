export interface ResourceRun {profile:string;preset:string;rate:number}
export function parseResourceRun(args:string[]):ResourceRun;
export function withResourceProject(config:ResourceRun,dependencies:{exec?:(args:string[],options:any)=>Promise<string>;log?:(s:string)=>void;signal?:AbortSignal},work:(context:{compose:(args:string[])=>Promise<string>;[key:string]:any})=>Promise<void>):Promise<number>;
export function runResourceExperiment(config:ResourceRun,dependencies?:any):Promise<number>;

export function waitFixtureJob(context:{project:string;compose:(args:string[])=>Promise<string>;docker:(args:string[])=>Promise<string>},service:string):Promise<true>;

export function validateResourceProof(proof:any,expectedAccounts:number,faults?:boolean):true;
