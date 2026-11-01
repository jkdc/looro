/** Bounded decoded response reader. Content-Length is never trusted as the boundary. */
export class ProxyResponseError extends Error {
  constructor(readonly outcome:'oversize'|'invalid_usage') {super(outcome);}
}
export async function readBoundedJSON(response:Response,{signal,maxBytes}:{signal:AbortSignal;maxBytes:number}):Promise<unknown> {
  const reader=response.body?.getReader();
  if(!reader)throw new ProxyResponseError('invalid_usage');
  const abort=()=>{void reader.cancel().catch(()=>{});};
  signal.addEventListener('abort',abort,{once:true});
  const chunks:Uint8Array[]=[];let bytes=0;
  try {
    signal.throwIfAborted();
    while(true) {
      const {done,value}=await reader.read();signal.throwIfAborted();if(done)break;
      bytes+=value.byteLength;if(bytes>maxBytes)throw new ProxyResponseError('oversize');chunks.push(value);
    }
    try {return JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch {throw new ProxyResponseError('invalid_usage');}
  } finally {signal.removeEventListener('abort',abort);await reader.cancel().catch(()=>{});}
}
