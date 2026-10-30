export function windowPlan(preset:string,rate?:number): {name:string;kind:string;durationS:number;targetRps:number}[];
export function acceptWindow(result:any):boolean;
export function summarizeWindows(results:any[]):{windows:number;offered:number;launched:number;useful:number;dropped:number;failedWindows:number;accepted:boolean};
export function mixedTask(index:number,fixture:any):any;
export function runResourceWorkload(config:any,env?:any):Promise<number>;
