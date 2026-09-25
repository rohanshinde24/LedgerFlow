import type { WorkspaceState } from "@/lib/workspace";

export function WorkspaceNotice({ state }: { state: Exclude<WorkspaceState, { kind: "ready" }> }) {
  if (state.kind === "core-unavailable") {
    return (
      <div className="notice">
        <strong>The financial core is not reachable.</strong>
        <p>
          Start PostgreSQL with <code>docker compose -f infra/docker-compose.yml up -d</code>, then run{" "}
          <code>mvn spring-boot:run</code> in <code>financial-core/</code>.
        </p>
      </div>
    );
  }

  return (
    <div className="notice">
      <strong>No business data yet.</strong>
      <p>
        Generate the synthetic dataset by starting the financial core with{" "}
        <code>LEDGERFLOW_GENERATE_DATASET=true</code>.
      </p>
    </div>
  );
}
