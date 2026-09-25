import type { MoneyView } from "./types";

export function formatMoney(money: MoneyView): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: money.currency,
    minimumFractionDigits: 2
  }).format(Number(money.amount));
}

export function formatDate(isoDate: string): string {
  return new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeZone: "UTC" }).format(
    new Date(`${isoDate}T00:00:00Z`)
  );
}

export function formatMonth(yearMonth: string): string {
  return new Intl.DateTimeFormat("en-US", { month: "short", year: "numeric", timeZone: "UTC" }).format(
    new Date(`${yearMonth}-01T00:00:00Z`)
  );
}

export function humanizeEnum(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

export function isNegative(money: MoneyView): boolean {
  return Number(money.amount) < 0;
}
