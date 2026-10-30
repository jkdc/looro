export function faultAction(kind:string,owned:{project:string;containers:Record<string,unknown>}):{kind:string;mode?:string;args?:string[]};
export function providerReply(mode:string,runId:string):{status:number;delayMs:number;hang:boolean;body:any};
export function readyPayload(payload:unknown):boolean;
