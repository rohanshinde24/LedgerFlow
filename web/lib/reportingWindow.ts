const DATASET_END_DATE = process.env.LEDGERFLOW_DATASET_END_DATE ?? "2025-12-31";

export interface ReportingWindow {
  from: string;
  to: string;
}

// The synthetic dataset is fixed in time, so reports anchor to its end date rather than "today".
export function trailingYear(): ReportingWindow {
  const end = new Date(`${DATASET_END_DATE}T00:00:00Z`);
  const start = new Date(end);
  start.setUTCFullYear(start.getUTCFullYear() - 1);
  start.setUTCDate(start.getUTCDate() + 1);
  return { from: start.toISOString().slice(0, 10), to: DATASET_END_DATE };
}
