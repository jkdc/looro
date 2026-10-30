export interface ResourceAllocation { cpus: number; memoryBytes: number }
export interface ResourceProfile { name: string; cpuLimit: number; memoryLimitBytes: number; services: Record<string,ResourceAllocation> }
export function resourceProfile(name: string): ResourceProfile;
export function validateResourceBudget(profile: ResourceProfile, activeServices: string[]): true;
export function resourceEnvironment(profile: ResourceProfile): Record<string,string>;
