---
name: git-checkpoint
description: 开始任何涉及修改/删除文件的自动化工作前，先做一次 git checkpoint 提交作为恢复基线；AI 误删误改时用 git reset --hard 一键回滚。适用于所有 git 仓库项目。用户要求"以后都这么来"。
---

# Git Checkpoint（工作前打点，出错一键恢复）

## 背景

AI 在自动化执行过程中可能会误删或误改文件。为防患未然，**任何修改动作开始之前**，先把当前工作区完整提交一次，作为恢复基线。一旦出错，`git reset --hard` 可瞬间恢复到该提交点。

## 什么时候做

在以下时机**开始动手修改文件之前**：

- 用户说"开始吧 / 帮我改 / 你来做"等即将进入实施阶段时
- 发现项目有未提交的改动，且接下来 AI 要动文件时
- 每次新的 Claude Code 会话，第一次要修改项目文件前

> 若项目当前就是干净工作区（`git status` 无改动），说明已有干净基线，可跳过本次 checkpoint。

## 怎么做（三步）

在项目目录执行：

```bash
# 1. 确认是 git 仓库（否则先 git init）
git rev-parse --git-dir >/dev/null 2>&1 || git init

# 2. 全量暂存 + 提交
git add .
git commit -m "checkpoint: 工作前基线 $(date +%Y%m%d-%H%M%S)"
```

提交后记住刚产生的 commit hash（`git rev-parse --short HEAD`），告知用户基线已建立。

## 出错了怎么恢复

AI 或用户判断出现误删/误改后，一键回滚到基线：

```bash
# 回滚到最近的 checkpoint（工作区 + 暂存区 + 提交全部还原）
git reset --hard HEAD
```

如果想精确回滚到某个 checkpoint 提交点：

```bash
git reset --hard <checkpoint的commit hash>
```

> ⚠️ `git reset --hard` 会**丢弃所有未提交的改动**，执行前先和用户确认确实要丢弃。

## 注意事项

- 此习惯适用于所有 git 仓库项目（用户全局偏好）
- 如果是用户自己先手工改了一半、又想让 AI 接手的情况：先按现状 checkpoint，再开始改，这样即使改砸了也能回到用户手工改完的状态
- 不要因为担心"提交太多"而省略 checkpoint——checkpoint 提交本身就是目的，不是负担
- 恢复用 `git reset --hard`（推荐）而非 `git revert`（revert 会产生反相提交，不适合"回到基线"的场景）
