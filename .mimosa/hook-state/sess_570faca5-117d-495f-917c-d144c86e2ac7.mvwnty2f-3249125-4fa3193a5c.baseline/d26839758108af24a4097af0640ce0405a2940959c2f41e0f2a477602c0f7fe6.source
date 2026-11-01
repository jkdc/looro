export interface BrowseColumnSpec {
  operators: readonly string[];
}

export interface BrowseFilter {
  field: string;
  operator: string;
  value: string;
}

export declare const BROWSE_UNARY_OPERATORS: Set<string>;

export declare function normalizeBrowseFilters(
  rawFilters: readonly unknown[] | null | undefined,
  columns: Record<string, BrowseColumnSpec> | null | undefined
): BrowseFilter[];

export declare function browseFilterQuery(
  filters: readonly BrowseFilter[] | null | undefined,
  columns: Record<string, BrowseColumnSpec> | null | undefined
): URLSearchParams;
