import type { ReactNode } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";

interface StatCardProps {
  title: string;
  icon: ReactNode;
  value: string;
  subtitle?: string;
  valueClassName?: string;
  className?: string;
}

export function StatCard({
  title,
  icon,
  value,
  subtitle,
  valueClassName,
  className,
}: StatCardProps) {
  return (
    <Card
      className={cn(
        "relative overflow-hidden transition-all hover:shadow-md border-l-4 border-l-primary/30",
        className,
      )}
    >
      <div className="absolute inset-0 bg-gradient-to-br from-primary/[0.02] to-transparent pointer-events-none" />

      <CardContent className="relative p-4">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
            {title}
          </span>
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-muted/50">
            {icon}
          </div>
        </div>
        <div
          className={cn(
            "text-2xl font-semibold tracking-tight metric-value",
            valueClassName,
          )}
        >
          {value}
        </div>
        {subtitle && (
          <p className="text-xs text-muted-foreground mt-1">{subtitle}</p>
        )}
      </CardContent>
    </Card>
  );
}
