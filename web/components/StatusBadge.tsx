import { humanizeEnum } from "@/lib/format";

export function StatusBadge({ status, warning = false }: { status: string; warning?: boolean }) {
  return <span className={warning ? "badge badge-warning" : "badge"}>{humanizeEnum(status)}</span>;
}
