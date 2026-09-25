import { FinancialCoreUnavailableError, primaryBusiness } from "./financialCoreClient";
import type { Business } from "./types";

export type WorkspaceState =
  | { kind: "ready"; business: Business }
  | { kind: "core-unavailable" }
  | { kind: "no-dataset" };

export async function loadWorkspace(): Promise<WorkspaceState> {
  try {
    const business = await primaryBusiness();
    return business ? { kind: "ready", business } : { kind: "no-dataset" };
  } catch (error) {
    if (error instanceof FinancialCoreUnavailableError) {
      return { kind: "core-unavailable" };
    }
    throw error;
  }
}
