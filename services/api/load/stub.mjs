import { createServer } from 'node:http';
import {providerReply} from './faults.mjs';
let telemetry=[],mode=process.env.STUB_MODE??'fast';
const server=createServer(async(req,res)=>{
  if(req.url==='/fixture/identity') {res.setHeader('content-type','application/json');res.end(JSON.stringify({runId:process.env.LOAD_RUN_ID}));return;}
  if(req.url==='/fixture/telemetry') {res.setHeader('content-type','application/json');res.end(JSON.stringify(telemetry));return;}
  const chunks=[];let size=0;
  for await(const chunk of req){size+=chunk.length;if(size>4*1024*1024){res.writeHead(413).end();return;}chunks.push(chunk);}
  if(req.url==='/fixture/mode') {
    let body;try {body=JSON.parse(Buffer.concat(chunks));}catch {}
    if(body?.runId!==process.env.LOAD_RUN_ID || !['fast','slow','hang','http-error'].includes(body?.mode)){res.writeHead(400).end();return;}
    mode=body.mode;res.end('{}');return;
  }
  if(req.url==='/v1/metrics') {
    try {telemetry.push(JSON.parse(Buffer.concat(chunks)));telemetry=telemetry.slice(-12);}catch {}
    res.setHeader('content-type','application/json');res.end('{}');return;
  }
  if(req.url==='/v1/traces'){res.setHeader('content-type','application/json');res.end('{}');return;}
  if(req.url!=='/v1/chat/completions'){res.writeHead(404).end();return;}
  const reply=providerReply(mode,process.env.LOAD_RUN_ID);
  if(reply.hang)return;
  if(reply.delayMs)await new Promise(r=>setTimeout(r,reply.delayMs));
  res.statusCode=reply.status;res.setHeader('content-type','application/json');res.end(JSON.stringify(reply.body));
});
server.listen(8081,'0.0.0.0');
