import { createServer } from 'node:http';
let telemetry=[],mode=process.env.STUB_MODE;
const server=createServer(async(req,res)=>{
  if(req.url==='/fixture/identity') {res.setHeader('content-type','application/json');res.end(JSON.stringify({runId:process.env.LOAD_RUN_ID}));return;}
  if(req.url==='/fixture/telemetry') {res.setHeader('content-type','application/json');res.end(JSON.stringify(telemetry));return;}
  const chunks=[];let size=0;
  for await(const chunk of req){size+=chunk.length;if(size>4*1024*1024){res.writeHead(413).end();return;}chunks.push(chunk);}
  if(req.url==='/fixture/mode') {
    let body;try {body=JSON.parse(Buffer.concat(chunks));}catch {}
    if(body?.runId!==process.env.LOAD_RUN_ID || !['fast','slow','hang'].includes(body?.mode)){res.writeHead(400).end();return;}
    mode=body.mode;res.end('{}');return;
  }
  if(req.url==='/v1/metrics') {
    try {telemetry.push(JSON.parse(Buffer.concat(chunks)));telemetry=telemetry.slice(-12);}catch {}
    res.setHeader('content-type','application/json');res.end('{}');return;
  }
  if(req.url==='/v1/traces'){res.setHeader('content-type','application/json');res.end('{}');return;}
  if(req.url!=='/v1/chat/completions'){res.writeHead(404).end();return;}
  if(mode==='hang') return;
  if(mode==='slow') await new Promise(r=>setTimeout(r,2000));
  res.setHeader('content-type','application/json');res.end(JSON.stringify({choices:[{message:{role:'assistant',content:process.env.LOAD_RUN_ID}}],usage:{prompt_tokens:10,completion_tokens:5}}));
});
server.listen(8081,'0.0.0.0');
