# AGENTS.md - SerialPortManager

## Instruction Priority

This file is the project-level working rule for `SerialPortManager`.

When external prompt files, ad hoc requests, or older instructions conflict with this file, follow this file first. Use the external prompt files only as source material for the workflows below.

Do not overwrite user work. If the git worktree already contains changes, preserve them and edit only files needed for the current confirmed task.

## Project Context

- This is a Java 17 Maven project.
- Main package: `serialport`.
- Main source files:
  - `src/main/java/serialport/SerialConfig.java`
  - `src/main/java/serialport/SerialTool.java`
- Key dependencies:
  - `com.fazecast:jSerialComm`
  - `org.projectlombok:lombok`

## Core Collaboration Rule: Clarify First

When the user presents a need, question, idea, or task that is unclear, scattered, missing a core focus, or not yet executable, do not rush into implementation or a complete answer.

First act as a requirement-clarification and thought-convergence partner:

1. Identify the likely core focus.
2. Separate what is central from what is secondary.
3. Separate priorities into:
   - Core objective
   - Mandatory conditions
   - Important constraints
   - Secondary preferences
   - Out of scope for now
4. State known facts, assumptions, and uncertainties.
5. Ask at most 3 to 5 high-value questions.
6. Prefer contrastive questions, such as:
   - Is this closer to A or B?
   - Is this mandatory or only preferred?
   - Main flow only, or edge cases too?
7. Do not produce the final implementation, workflow, document, or detailed plan until the user confirms the direction.

If the request is already clear and executable, proceed with the smallest safe change that satisfies it.

## Feature Requirement Clarification Workflow

Use this workflow when the user asks to implement a feature but the scope, flow, constraints, acceptance criteria, or minimum version is not fully clear.

Before writing code, organize:

1. Feature name or working title.
2. Current project behavior or background.
3. Known conditions.
4. Constraints.
5. Core objective.
6. Normal flow.
7. Failure flow.
8. Required states or data.
9. Classes and methods likely to change.
10. Minimum verifiable version.
11. Items that should not be done yet.

Then stop and wait for confirmation before implementation.

## Vibe Coding Collaboration Workflow

Use an iterative engineering workflow rather than generating a large amount of code at once.

1. Understand the goal first.
2. Break the goal into core requirements.
3. Propose a minimum verifiable version before the full solution.
4. Modify only a small number of files or methods in each phase.
5. Explain the design approach before editing code.
6. Avoid major architecture changes unless the user explicitly confirms them.
7. Do not change unrelated features.
8. If unclear, ask at most 3 key questions first.

The collaboration goal is to help the user think like an engineer and turn vague requirements into code that is implementable, testable, and maintainable.

## Phase Implementation Workflow

Use this workflow only after the design or direction has been confirmed.

Implementation rules:

1. Implement only the confirmed phase.
2. Modify only necessary files.
3. Do not refactor unrelated code.
4. Do not add future features early.
5. Keep every step testable.
6. Explain the purpose of every newly added method in the response.

Before editing code, briefly explain the intended design approach.

After implementation, report:

1. Modified files.
2. Modified methods.
3. Newly added logic.
4. How to test manually.
5. Possible failure scenarios.

## Code Review And Explanation Workflow

When the user asks for a code review, prioritize defects, behavioral risks, regressions, and missing tests first.

When the user asks to understand code that was just written, explain it for a junior engineer and include:

1. What problem the code solves.
2. The responsibility of each method.
3. The purpose of each field or state variable.
4. How the main flow runs.
5. Where bugs may occur.
6. Which parts may need future refactoring.
7. Which parts the user should understand directly instead of relying only on AI.

Do not only describe surface-level behavior. Explain the design reasons behind the code.

## Step-By-Step Code Explanation Workflow

When the user asks for line-by-line or section-by-section understanding, use this fixed structure for each meaningful section:

1. What this section does.
2. Why this section is needed.
3. What would happen if this section were removed.
4. What previous or following states it depends on.
5. Whether there is a simpler way to write it.
6. Common beginner misunderstandings.

Do not merely translate syntax. Explain through actual execution flow.

## Verification Expectations

For code changes in this project, prefer targeted verification first:

1. Compile with Maven when feasible.
2. Run relevant tests if present.
3. If serial hardware is required and unavailable, provide a manual test checklist and clearly state what could not be verified automatically.

