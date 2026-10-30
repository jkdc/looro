import type {ResourceProfile,ResourceAllocation} from './resource-profiles.mjs';
export function assertOwnedContainer(inspect: unknown,project: string,services: string[]): true;
export function assertEffectiveLimits(inspect: unknown,expected: ResourceAllocation): true;
export function summarizeContainer(inspect: unknown,stats?: Record<string,string>,cgroup?: string|null): {
 service: string|null; cpus:number|null;memoryLimitBytes:number|null;cpuPercent:number|null;memoryUsedBytes:number|null;
 networkBytes:(number|null)[];blockBytes:(number|null)[];cpuUsageUsec:number|null;throttledUsec:number|null;throttledPeriods:number|null;oomKilled:boolean|null;restarts:number|null;
};
export function sampleContainers(project: string,containers: (string|{id:string;service:string})[],profile: ResourceProfile,exec: (args:string[])=>Promise<string>): Promise<unknown[]>;
