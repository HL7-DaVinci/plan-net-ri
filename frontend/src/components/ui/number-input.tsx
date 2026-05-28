import { ChevronDown, ChevronUp } from "lucide-react";
import { type ComponentProps, useCallback, useRef } from "react";

import { cn } from "@/lib/utils";

type NumberInputProps = Omit<ComponentProps<"input">, "type">;

function NumberInput({ className, ...props }: NumberInputProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const isLocked = Boolean(props.disabled || props.readOnly);

  const step = useCallback((direction: "up" | "down") => {
    const input = inputRef.current;
    if (!input || input.disabled || input.readOnly) return;
    if (direction === "up") {
      input.stepUp();
    } else {
      input.stepDown();
    }
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
  }, []);

  return (
    <div className="relative flex items-center">
      <input
        ref={inputRef}
        type="number"
        data-slot="input"
        className={cn(
          "file:text-foreground placeholder:text-muted-foreground selection:bg-primary selection:text-primary-foreground dark:bg-input/30 border-input h-9 w-full min-w-0 rounded-md border bg-transparent px-3 py-1 pr-7 text-base shadow-xs transition-[color,box-shadow] outline-none file:inline-flex file:h-7 file:border-0 file:bg-transparent file:text-sm file:font-medium disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50 md:text-sm",
          "focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]",
          "aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive",
          "[&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none [-moz-appearance:textfield]",
          className,
        )}
        {...props}
      />
      <div className="absolute right-px top-px bottom-px flex flex-col border-l border-input">
        <button
          type="button"
          tabIndex={-1}
          aria-label="Increment"
          disabled={isLocked}
          className="flex w-6 flex-1 items-center justify-center rounded-tr-md text-muted-foreground/60 transition-colors hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-transparent disabled:hover:text-muted-foreground/60"
          onClick={() => step("up")}
        >
          <ChevronUp className="size-3" />
        </button>
        <div className="border-t border-input" />
        <button
          type="button"
          tabIndex={-1}
          aria-label="Decrement"
          disabled={isLocked}
          className="flex w-6 flex-1 items-center justify-center rounded-br-md text-muted-foreground/60 transition-colors hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-transparent disabled:hover:text-muted-foreground/60"
          onClick={() => step("down")}
        >
          <ChevronDown className="size-3" />
        </button>
      </div>
    </div>
  );
}

export { NumberInput };
