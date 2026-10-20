#!/usr/bin/env python3
"""Convert the 21 static HTML prototypes into an importable native WeChat mini-program.

This is a visual conversion, not a full product rewrite:
- HTML layout/text is translated to WXML.
- Tailwind utility classes used by the prototypes are compiled into WXSS.
- Material Symbols icon names become emoji (no remote icon font needed).
- Inline SVG is embedded as a base64 image.
- Prototype scripts/forms are intentionally not reimplemented.
"""
from __future__ import annotations

import base64
import html
import json
import os
import re
import shutil
import urllib.request
from pathlib import Path
from bs4 import BeautifulSoup, Comment, Doctype, NavigableString, Tag

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "stitch_web_repository_frontend_designer"
OUT = ROOT / "miniprogram-demo"

PAGES = [
    ("_1", "service", "服务", "Service · 服务大厅"),
    ("_2", "mutual", "互助", "Mutual · 互助广场"),
    ("_3", "opportunity", "机会", "Opportunity · 机会列表"),
    ("_4", "home", "首页", "Home · 首页"),
    ("_5", "profile", "我的", "Profile · 个人主页"),
    ("_6", "precheck", "材料预检", "Pre Check Flow · 材料预检"),
    ("_7", "seat-select", "选座", "Seat Selection · 平面选座"),
    ("_8", "library", "图书馆预约", "Library Seat Reservation · 图书馆预约"),
    ("_9", "voucher", "预约凭证", "Reservation Voucher · 预约凭证"),
    ("_10", "reservation-confirm", "预约确认", "Reservation Confirm · 预约确认"),
    ("_11", "mutual-create", "发布互助", "Create Mutual Aid Post · 发布互助"),
    ("_12", "mutual-detail", "互助详情", "Mutual Aid Detail · 互助详情"),
    ("_13", "chat", "私信", "Direct Communication · 私信"),
    ("_14", "login", "登录", "Login · 登录"),
    ("_15", "phone-bind", "绑定手机号", "Phone Bind · 绑定手机号"),
    ("_16", "verify", "学生认证", "Verify · 学生认证"),
    ("_17", "vault", "材料保险箱", "Material Vault · 材料保险箱"),
    ("_18", "network-error", "网络异常", "Network Exception · 网络异常"),
    ("_19", "maintenance", "服务维护", "Service Maintenance · 服务维护"),
    ("_20", "permission-denied", "权限不足", "Permission Denied · 权限不足"),
    ("_21", "success", "操作结果", "Action Success Feedback · 操作结果"),
]

FIRST = (SRC / "_1" / "code.html").read_text(encoding="utf-8", errors="ignore")
m = re.search(r'"colors"\s*:\s*\{(.*?)\}\s*,\s*"borderRadius"', FIRST, re.S)
PALETTE = dict(re.findall(r'"([A-Za-z0-9-]+)"\s*:\s*"([^"]+)"', m.group(1))) if m else {}
PALETTE.update({"white": "#ffffff", "black": "#000000", "transparent": "transparent", "current": "currentColor"})

FONT_SIZES = {
    "body-md": ("14px", "20px", "400"),
    "headline-sm": ("17px", "24px", "600"),
    "label-sm": ("11px", "14px", "500"),
    "label-md": ("13px", "18px", "600"),
    "headline-xl": ("24px", "32px", "700"),
    "body-lg": ("15px", "22px", "400"),
    "caption": ("10px", "12px", "500"),
    "body-sm": ("12px", "16px", "400"),
    "headline-lg": ("20px", "28px", "600"),
}
NAMED_SPACING = {
    "space-xs": "4px", "space-sm": "8px", "space-md": "12px", "space-lg": "16px",
    "space-xl": "24px", "margin": "16px", "gutter": "12px",
}
RADII = {"DEFAULT": "4px", "lg": "8px", "xl": "12px", "full": "9999px", "none": "0px"}
FONT_FAMILY = '-apple-system, BlinkMacSystemFont, "PingFang SC", "Helvetica Neue", "Microsoft YaHei", Arial, sans-serif'

ICONS = {
    "verified": "✅", "person": "👤", "check_circle": "✅", "verified_user": "🛡️",
    "more_horiz": "⋯", "arrow_back_ios_new": "‹", "info": "ℹ️", "radio_button_checked": "◉",
    "chevron_right": "›", "check": "✓", "badge": "🎖️", "visibility": "👁️",
    "account_balance": "🏛️", "schedule": "🕒", "refresh": "🔄", "lock": "🔒",
    "grid_view": "▦", "close": "✕", "task_alt": "✔️", "star": "⭐", "shield": "🛡️",
    "school": "🏫", "explore": "🧭", "diversity_1": "👥", "arrow_forward": "→",
    "adjust": "◐", "warning": "⚠️", "security": "🛡️", "expand_more": "⌄", "done": "✓",
    "auto_awesome": "✨", "swap_horiz": "⇄", "search": "🔍", "menu_book": "📖",
    "location_on": "📍", "groups": "👥", "gavel": "⚖️", "done_all": "✓✓",
    "domain_verification": "🏛️", "description": "📄", "archive": "🗄️", "wifi": "📶",
    "wb_sunny": "☀️", "volunteer_activism": "🤝", "thumb_up": "👍",
    "swap_horizontal_circle": "⇄", "smartphone": "📱", "shield_with_heart": "🛡️",
    "send": "➤", "qr_code_scanner": "▣", "progress_activity": "⟳", "policy": "📜",
    "pin_drop": "📍", "notifications_active": "🔔", "image": "🖼️", "how_to_vote": "🗳️",
    "folder_zip": "🗜️", "fingerprint": "🔎", "file_download": "⬇️", "favorite": "♥",
    "fact_check": "☑️", "event_seat": "💺", "content_copy": "📋", "chair": "🪑",
    "bookmark_border": "🔖", "assignment_turned_in": "✅", "article": "📄",
    "add_circle": "➕", "workspace_premium": "🏆", "window": "🪟", "wifi_off": "📵",
    "wallet": "👛", "volume_off": "🔇", "volume_mute": "🔈", "visibility_off": "🚫",
    "videocam": "📹", "upload_file": "⬆️", "update": "🔄", "unfold_more": "⌄",
    "travel_explore": "🧭", "home_repair_service": "🔧", "restaurant": "🍽️", "forum": "💬",
    "local_library": "📚", "history": "🕘", "receipt_long": "🧾", "payments": "💳",
    "settings": "⚙️", "logout": "🚪", "add": "＋", "remove": "−", "edit": "✏️",
    "delete": "🗑️", "filter_list": "☰", "sort": "↕", "calendar_month": "📅",
    "access_time": "🕒", "place": "📍", "phone": "📞", "mail": "✉️",
}

BLOCK_TAGS = {
    "div", "section", "header", "main", "footer", "nav", "article", "aside", "form",
    "ul", "ol", "li", "figure", "figcaption", "blockquote", "table", "thead", "tbody",
    "tr", "td", "th", "dl", "dt", "dd", "details", "summary", "fieldset", "legend",
    "p", "h1", "h2", "h3", "h4", "h5", "h6", "label",
}
INLINE_TAGS = {"span", "strong", "b", "em", "i", "small", "time", "abbr", "code", "pre", "mark", "del", "ins", "sub", "sup"}
LAYOUT_HINTS = ("flex", "grid", "block", "w-", "h-", "p-", "px-", "py-", "m-", "mt-", "mb-", "rounded", "bg-", "border", "absolute", "relative", "fixed", "sticky")

SKIP_VARIANTS = ("hover:", "active:", "focus:", "focus-within:", "placeholder:", "dark:", "group-", "peer-",
                 "first:", "last:", "odd:", "even:", "before:", "after:", "md:", "lg:", "sm:", "xl:", "2xl:")


def esc_text(s: str) -> str:
    s = re.sub(r"\s+", " ", s)
    s = s.replace("{{", "{ {")
    return html.escape(s, quote=False)


def esc_attr(s: str) -> str:
    return html.escape(s, quote=True)


def spacing(v: str) -> str | None:
    if v in NAMED_SPACING:
        return NAMED_SPACING[v]
    if v == "px":
        return "1px"
    if v == "full":
        return "100%"
    if v == "auto":
        return "auto"
    if v == "screen":
        return "100vw"
    if v == "min":
        return "min-content"
    if v == "max":
        return "max-content"
    if v == "fit":
        return "fit-content"
    if "/" in v:
        a, b = v.split("/", 1)
        try:
            return f"{float(a) / float(b) * 100:.4f}%"
        except ValueError:
            return None
    if re.fullmatch(r"-?\d+(\.\d+)?", v):
        # Mini-program WXSS is safest with px; the prototypes assume a 16px root.
        return f"{float(v) * 4:g}px"
    return None


def arbitrary(v: str) -> str:
    return v.replace("_", " ")


def color_of(name: str) -> str | None:
    if not name:
        return None
    alpha = None
    if "/" in name:
        base, a = name.split("/", 1)
        if a.startswith("[") and a.endswith("]"):
            alpha = a[1:-1]
        elif a.isdigit():
            alpha = str(int(a) / 100)
        else:
            return None
        name = base
    if name.startswith("[#") and name.endswith("]"):
        c = name[2:-1]
    elif name in PALETTE:
        c = PALETTE[name]
    elif name.startswith("#") and name.endswith("]"):
        c = name[1:-1]
    else:
        return None
    if alpha and c.startswith("#") and len(c) in (7, 4):
        if len(c) == 4:
            c = "#" + "".join(ch * 2 for ch in c[1:])
        r, g, b = int(c[1:3], 16), int(c[3:5], 16), int(c[5:7], 16)
        return f"rgba({r},{g},{b},{alpha})"
    return c


def size_value(v: str) -> str | None:
    if v.startswith("[") and v.endswith("]"):
        return arbitrary(v[1:-1])
    return spacing(v)


# ---------- Tailwind utility compiler ----------

def class_css(tok: str) -> tuple[list[str], list[str]]:
    """Return (normal rules, late rules) for a tailwind token. Empty list = unsupported."""
    important = tok.startswith("!")
    if important:
        tok = tok[1:]
    if any(tok.startswith(p) for p in SKIP_VARIANTS) or ":" in tok:
        return [], []
    sign = ""
    if tok.startswith("-") and not tok.startswith("--"):
        sign, tok = "-", tok[1:]

    def decl(*pairs):
        return [f"{k}:{v}{'!important' if important else ''}" for k, v in pairs]

    # layout
    simple = {
        "flex": [("display", "flex")], "grid": [("display", "grid")],
        "hidden": [("display", "none")], "block": [("display", "block")],
        "inline": [("display", "inline")], "inline-block": [("display", "inline-block")],
        "inline-flex": [("display", "inline-flex")], "contents": [("display", "contents")],
        "flex-row": [("flex-direction", "row")], "flex-col": [("flex-direction", "column")],
        "flex-row-reverse": [("flex-direction", "row-reverse")], "flex-col-reverse": [("flex-direction", "column-reverse")],
        "flex-wrap": [("flex-wrap", "wrap")], "flex-nowrap": [("flex-wrap", "nowrap")],
        "flex-1": [("flex", "1 1 0%")], "flex-auto": [("flex", "1 1 auto")],
        "flex-initial": [("flex", "0 1 auto")], "flex-none": [("flex", "none")],
        "grow": [("flex-grow", "1")], "grow-0": [("flex-grow", "0")],
        "shrink": [("flex-shrink", "1")], "shrink-0": [("flex-shrink", "0")],
        "relative": [("position", "relative")], "absolute": [("position", "absolute")],
        "fixed": [("position", "fixed")], "sticky": [("position", "sticky")],
        "static": [("position", "static")],
        "overflow-hidden": [("overflow", "hidden")], "overflow-visible": [("overflow", "visible")],
        "overflow-x-auto": [("overflow-x", "auto")], "overflow-y-auto": [("overflow-y", "auto")],
        "overflow-x-hidden": [("overflow-x", "hidden")], "overflow-y-hidden": [("overflow-y", "hidden")],
        "truncate": [("overflow", "hidden"), ("text-overflow", "ellipsis"), ("white-space", "nowrap")],
        "text-ellipsis": [("text-overflow", "ellipsis")],
        "whitespace-nowrap": [("white-space", "nowrap")], "whitespace-normal": [("white-space", "normal")],
        "whitespace-pre-line": [("white-space", "pre-line")],
        "break-words": [("word-break", "break-word")], "break-all": [("word-break", "break-all")],
        "select-none": [("user-select", "none")], "select-text": [("user-select", "text")],
        "pointer-events-none": [("pointer-events", "none")], "pointer-events-auto": [("pointer-events", "auto")],
        "outline-none": [("outline", "none")], "appearance-none": [("appearance", "none")],
        "resize-none": [("resize", "none")],
        "object-cover": [("object-fit", "cover")], "object-contain": [("object-fit", "contain")],
        "object-center": [("object-position", "center")],
        "bg-cover": [("background-size", "cover")], "bg-contain": [("background-size", "contain")],
        "bg-center": [("background-position", "center")], "bg-top": [("background-position", "top")],
        "bg-bottom": [("background-position", "bottom")], "bg-left": [("background-position", "left")],
        "bg-right": [("background-position", "right")], "bg-no-repeat": [("background-repeat", "no-repeat")],
        "bg-repeat": [("background-repeat", "repeat")],
        "uppercase": [("text-transform", "uppercase")], "lowercase": [("text-transform", "lowercase")],
        "capitalize": [("text-transform", "capitalize")], "normal-case": [("text-transform", "none")],
        "underline": [("text-decoration", "underline")], "line-through": [("text-decoration", "line-through")],
        "no-underline": [("text-decoration", "none")],
        "text-left": [("text-align", "left")], "text-center": [("text-align", "center")],
        "text-right": [("text-align", "right")], "text-justify": [("text-align", "justify")],
        "font-thin": [("font-weight", "100")], "font-extralight": [("font-weight", "200")],
        "font-light": [("font-weight", "300")], "font-normal": [("font-weight", "400")],
        "font-medium": [("font-weight", "500")], "font-semibold": [("font-weight", "600")],
        "font-bold": [("font-weight", "700")], "font-extrabold": [("font-weight", "800")],
        "font-black": [("font-weight", "900")],
        "italic": [("font-style", "italic")], "not-italic": [("font-style", "normal")],
        "sr-only": [("position", "absolute"), ("width", "1px"), ("height", "1px"), ("padding", "0"),
                    ("margin", "-1px"), ("overflow", "hidden"), ("clip", "rect(0,0,0,0)"),
                    ("white-space", "nowrap"), ("border-width", "0")],
        "min-h-screen": [("min-height", "100vh")], "h-screen": [("height", "100vh")],
        "w-screen": [("width", "100vw")], "min-w-0": [("min-width", "0")], "min-w-full": [("min-width", "100%")],
        "mx-auto": [("margin-left", "auto"), ("margin-right", "auto")],
        "cursor-pointer": [("cursor", "pointer")], "cursor-default": [("cursor", "default")],
        "antialiased": [("-webkit-font-smoothing", "antialiased")],
    }
    if tok in simple:
        return decl(*simple[tok]), []

    # colors
    if tok.startswith("bg-"):
        rest = tok[3:]
        if rest.startswith("gradient-to-"):
            direction = {"r": "to right", "l": "to left", "t": "to top", "b": "to bottom",
                         "tr": "to top right", "tl": "to top left", "br": "to bottom right", "bl": "to bottom left"}.get(rest[len("gradient-to-"):])
            if direction:
                return [], decl(("background-image", f"linear-gradient({direction},var(--tw-from,transparent),var(--tw-via,var(--tw-from,transparent)),var(--tw-to,transparent))"))
        if rest.startswith("[url(") and rest.endswith(")]"):
            return decl(("background-image", f"url({arbitrary(rest[5:-2])})")), []
        c = color_of(rest)
        if c:
            return decl(("background-color", c)), []
    if tok.startswith("from-"):
        c = color_of(tok[5:])
        if c:
            return decl(("--tw-from", c)), []
    if tok.startswith("via-"):
        c = color_of(tok[4:])
        if c:
            return decl(("--tw-via", c)), []
    if tok.startswith("to-"):
        c = color_of(tok[3:])
        if c:
            return decl(("--tw-to", c)), []

    if tok.startswith("text-"):
        rest = tok[5:]
        if rest in FONT_SIZES:
            size, line, weight = FONT_SIZES[rest]
            return decl(("font-size", size), ("line-height", line), ("font-weight", weight)), []
        if rest.startswith("[") and rest.endswith("]"):
            v = arbitrary(rest[1:-1])
            if v.startswith("#") or v.startswith("rgb"):
                return decl(("color", color_of(rest[1:-1]) or v)), []
            return decl(("font-size", v)), []
        default_sizes = {"xs": "12px", "sm": "14px", "base": "16px", "lg": "18px", "xl": "20px",
                         "2xl": "24px", "3xl": "30px", "4xl": "36px", "5xl": "48px"}
        if rest in default_sizes:
            return decl(("font-size", default_sizes[rest])), []
        c = color_of(rest)
        if c:
            return decl(("color", c)), []
    if tok.startswith("border-"):
        rest = tok[7:]
        if rest in ("", "0", "2", "4", "8"):
            w = "1px" if rest == "" else rest + "px"
            return decl(("border-width", w), ("border-style", "solid")), []
        if rest in ("t", "b", "l", "r"):
            return decl((f"border-{ {'t':'top','b':'bottom','l':'left','r':'right'}[rest] }-width", "1px"), ("border-style", "solid")), []
        if re.fullmatch(r"[tblr]-[0-9]+", rest):
            side, n = rest.split("-")
            return decl((f"border-{ {'t':'top','b':'bottom','l':'left','r':'right'}[side] }-width", n + "px"), ("border-style", "solid")), []
        c = color_of(rest)
        if c:
            return decl(("border-color", c)), []
    if tok == "border":
        return decl(("border-width", "1px"), ("border-style", "solid"), ("border-color", PALETTE.get("outline-variant", "#c1c7ce"))), []

    # rounding
    if tok.startswith("rounded"):
        rest = tok[len("rounded"):].lstrip("-")
        r = RADII.get(rest, None)
        if r:
            side = ""
            if rest.startswith("t-"):
                side = "-top-left-radius:-top-right-radius"
            # basic: rounded, rounded-lg/xl/full, rounded-[...]
        if tok.startswith("rounded-["):
            return decl(("border-radius", arbitrary(tok[len("rounded-["):-1]))), []
        if rest in RADII:
            return decl(("border-radius", RADII[rest])), []
        if rest == "":
            return decl(("border-radius", RADII["DEFAULT"])), []
        m2 = re.fullmatch(r"([tblrse]+)-(.+)", rest)
        if m2:
            corners = {
                "t": ["top-left", "top-right"], "r": ["top-right", "bottom-right"],
                "b": ["bottom-right", "bottom-left"], "l": ["top-left", "bottom-left"],
                "tl": ["top-left"], "tr": ["top-right"], "br": ["bottom-right"], "bl": ["bottom-left"],
                "s": ["top-left", "bottom-left"], "e": ["top-right", "bottom-right"],
            }.get(m2.group(1))
            val = RADII.get(m2.group(2), m2.group(2).replace("]", ""))
            if corners:
                return decl(*[(f"border-{c}-radius", val) for c in corners]), []

    # spacing: p/m/gap/space
    for prefix, props in (("p", ["padding"]), ("pt", ["padding-top"]), ("pr", ["padding-right"]),
                          ("pb", ["padding-bottom"]), ("pl", ["padding-left"]),
                          ("px", ["padding-left", "padding-right"]), ("py", ["padding-top", "padding-bottom"]),
                          ("m", ["margin"]), ("mt", ["margin-top"]), ("mr", ["margin-right"]),
                          ("mb", ["margin-bottom"]), ("ml", ["margin-left"]),
                          ("mx", ["margin-left", "margin-right"]), ("my", ["margin-top", "margin-bottom"]),
                          ("gap", ["gap"]), ("gap-x", ["column-gap"]), ("gap-y", ["row-gap"])):
        if tok.startswith(prefix + "-"):
            v = size_value(tok[len(prefix) + 1:])
            if v:
                if sign:
                    v = f"-{v}"
                return decl(*[(p, v) for p in props]), []
    if tok.startswith("space-y-"):
        v = size_value(tok[len("space-y-"):])
        if v:
            return [f"__SPACEY__:{v}"], []
    if tok.startswith("space-x-"):
        v = size_value(tok[len("space-x-"):])
        if v:
            return [f"__SPACEX__:{v}"], []

    # size
    if tok.startswith("w-") or tok.startswith("h-") or tok.startswith("min-w-") or tok.startswith("min-h-") or tok.startswith("max-w-") or tok.startswith("max-h-"):
        for prefix, prop in (("min-w-", "min-width"), ("min-h-", "min-height"), ("max-w-", "max-width"),
                             ("max-h-", "max-height"), ("w-", "width"), ("h-", "height")):
            if tok.startswith(prefix):
                rest = tok[len(prefix):]
                if rest.startswith("[") and rest.endswith("]"):
                    return decl((prop, arbitrary(rest[1:-1]))), []
                v = size_value(rest)
                if v:
                    return decl((prop, v)), []
                break

    # grid
    if tok.startswith("grid-cols-"):
        rest = tok[len("grid-cols-"):]
        if rest.startswith("[") and rest.endswith("]"):
            return decl(("grid-template-columns", arbitrary(rest[1:-1]))), []
        if rest.isdigit():
            return decl(("grid-template-columns", f"repeat({rest},minmax(0,1fr))")), []
    if tok.startswith("grid-rows-"):
        rest = tok[len("grid-rows-"):]
        if rest.isdigit():
            return decl(("grid-template-rows", f"repeat({rest},minmax(0,1fr))")), []
    if tok.startswith("col-span-"):
        rest = tok[len("col-span-"):]
        if rest.isdigit():
            return decl(("grid-column", f"span {rest} / span {rest}")), []
    if tok.startswith("row-span-"):
        rest = tok[len("row-span-"):]
        if rest.isdigit():
            return decl(("grid-row", f"span {rest} / span {rest}")), []

    # flex alignment
    align = {
        "items-start": ("align-items", "flex-start"), "items-end": ("align-items", "flex-end"),
        "items-center": ("align-items", "center"), "items-baseline": ("align-items", "baseline"),
        "items-stretch": ("align-items", "stretch"), "justify-start": ("justify-content", "flex-start"),
        "justify-end": ("justify-content", "flex-end"), "justify-center": ("justify-content", "center"),
        "justify-between": ("justify-content", "space-between"), "justify-around": ("justify-content", "space-around"),
        "justify-evenly": ("justify-content", "space-evenly"), "self-start": ("align-self", "flex-start"),
        "self-end": ("align-self", "flex-end"), "self-center": ("align-self", "center"),
        "self-stretch": ("align-self", "stretch"), "content-center": ("align-content", "center"),
        "content-between": ("align-content", "space-between"), "content-start": ("align-content", "flex-start"),
        "content-end": ("align-content", "flex-end"),
    }
    if tok in align:
        return decl(align[tok]), []

    # z / opacity / transforms
    if tok.startswith("z-"):
        v = tok[2:]
        if v.startswith("[") and v.endswith("]"):
            v = arbitrary(v[1:-1])
        if v.isdigit() or v == "auto":
            return decl(("z-index", v)), []
    if tok.startswith("opacity-"):
        v = tok[len("opacity-"):]
        if v.startswith("[") and v.endswith("]"):
            v = arbitrary(v[1:-1])
        elif v.isdigit():
            v = str(int(v) / 100)
        return decl(("opacity", v)), []
    if tok.startswith("translate-x-") or tok.startswith("translate-y-"):
        axis = "X" if tok.startswith("translate-x-") else "Y"
        v = size_value(tok[len("translate-x-"):] if axis == "X" else tok[len("translate-y-"):])
        if v:
            if sign:
                v = f"-{v}"
            return decl(("transform", f"translate{axis}({v})")), []
    if tok.startswith("scale-"):
        v = tok[len("scale-"):]
        if v.startswith("[") and v.endswith("]"):
            v = arbitrary(v[1:-1])
        elif v.replace(".", "", 1).isdigit():
            v = str(float(v) / 100)
        return decl(("transform", f"scale({v})")), []
    if tok.startswith("rotate-"):
        v = tok[len("rotate-"):].replace("deg", "")
        if v.startswith("[") and v.endswith("]"):
            v = arbitrary(v[1:-1])
        return decl(("transform", f"rotate({v}deg)")), []
    if tok.startswith("blur-"):
        v = tok[len("blur-"):]
        if v == "none":
            return decl(("filter", "none")), []
        if v.startswith("[") and v.endswith("]"):
            v = arbitrary(v[1:-1])
        elif v in ("sm", "md", "lg", "xl", "2xl", "3xl"):
            v = {"sm": "4px", "md": "12px", "lg": "16px", "xl": "24px", "2xl": "40px", "3xl": "64px"}[v]
        elif v.isdigit():
            v += "px"
        return decl(("filter", f"blur({v})")), []

    # aspect / line clamp
    if tok == "aspect-square":
        return decl(("aspect-ratio", "1 / 1")), []
    if tok == "aspect-video":
        return decl(("aspect-ratio", "16 / 9")), []
    if tok.startswith("aspect-["):
        return decl(("aspect-ratio", arbitrary(tok[len("aspect-["):-1]))), []
    if tok.startswith("line-clamp-"):
        n = tok[len("line-clamp-"):]
        if n.isdigit():
            return decl(("display", "-webkit-box"), ("-webkit-line-clamp", n), ("-webkit-box-orient", "vertical"), ("overflow", "hidden")), []

    # shadows
    shadows = {
        "shadow-sm": "0 1px 2px 0 rgba(0,0,0,0.05)",
        "shadow": "0 1px 3px 0 rgba(0,0,0,0.1),0 1px 2px -1px rgba(0,0,0,0.1)",
        "shadow-md": "0 4px 6px -1px rgba(0,0,0,0.1),0 2px 4px -2px rgba(0,0,0,0.1)",
        "shadow-lg": "0 10px 15px -3px rgba(0,0,0,0.1),0 4px 6px -4px rgba(0,0,0,0.1)",
        "shadow-xl": "0 20px 25px -5px rgba(0,0,0,0.1),0 8px 10px -6px rgba(0,0,0,0.1)",
        "shadow-2xl": "0 25px 50px -12px rgba(0,0,0,0.25)",
        "shadow-inner": "inset 0 2px 4px 0 rgba(0,0,0,0.05)",
        "shadow-none": "none",
    }
    if tok in shadows:
        return decl(("box-shadow", shadows[tok])), []
    if tok.startswith("shadow-["):
        return decl(("box-shadow", arbitrary(tok[len("shadow-["):-1]))), []

    # font family tokens
    if tok.startswith("font-") and tok[5:] in FONT_SIZES:
        return decl(("font-family", FONT_FAMILY)), []
    if tok == "font-mono":
        return decl(("font-family", "monospace")), []
    if tok == "font-sans":
        return decl(("font-family", FONT_FAMILY)), []

    # line-height / tracking
    leading = {"none": "1", "tight": "1.25", "snug": "1.375", "normal": "1.5", "relaxed": "1.625", "loose": "2"}
    if tok.startswith("leading-"):
        v = tok[len("leading-"):]
        v = leading.get(v, arbitrary(v[1:-1]) if v.startswith("[") else None)
        if v:
            return decl(("line-height", v)), []
    tracking = {"tighter": "-0.05em", "tight": "-0.025em", "normal": "0", "wide": "0.025em", "wider": "0.05em", "widest": "0.1em"}
    if tok.startswith("tracking-"):
        v = tok[len("tracking-"):]
        v = tracking.get(v, arbitrary(v[1:-1]) if v.startswith("[") else None)
        if v:
            return decl(("letter-spacing", v)), []

    # positioning
    for prefix, prop in (("inset-x-", ("left", "right")), ("inset-y-", ("top", "bottom")),
                         ("top-", ("top",)), ("bottom-", ("bottom",)), ("left-", ("left",)), ("right-", ("right",))):
        if tok.startswith(prefix):
            rest = tok[len(prefix):]
            v = size_value(rest)
            if v:
                if sign:
                    v = f"-{v}"
                return decl(*[(p, v) for p in prop]), []

    # transition
    if tok.startswith("transition"):
        return decl(("transition-property", "all"), ("transition-duration", "150ms"), ("transition-timing-function", "ease")), []
    if tok.startswith("duration-"):
        v = tok[len("duration-"):]
        if v.isdigit():
            return decl(("transition-duration", v + "ms")), []
    if tok == "ease-in-out":
        return decl(("transition-timing-function", "ease-in-out")), []
    if tok == "ease-in":
        return decl(("transition-timing-function", "ease-in")), []
    if tok == "ease-out":
        return decl(("transition-timing-function", "ease-out")), []

    return [], []


def build_class_map(soups):
    tokens = set()
    for soup in soups:
        for tag in soup.find_all(True):
            cls = tag.get("class")
            if not cls:
                continue
            for tok in cls:
                if tok == "material-symbols-outlined":
                    continue
                tokens.add(tok)
    # custom safe classes
    for extra in ("mi", "checkbox", "pb-safe", "pt-safe", "pr-capsule", "proto-card"):
        tokens.add(extra)
    mapping = {}
    for i, tok in enumerate(sorted(tokens)):
        mapping[tok] = f"tw_{i:04d}"
    return mapping


def safe_classes(tag, mapping, extra=()):
    result = []
    for tok in tag.get("class", []) or []:
        if tok == "material-symbols-outlined":
            continue
        if tok in mapping:
            result.append(mapping[tok])
    for x in extra:
        if x in mapping:
            result.append(mapping[x])
    return " ".join(dict.fromkeys(result))


def make_image_urls(soup_list):
    urls = set()
    for soup in soup_list:
        for img in soup.find_all("img"):
            if img.get("src", "").startswith("http"):
                urls.add(img["src"])
        for tag in soup.find_all(True):
            style = tag.get("style", "")
            for u in re.findall(r"url\(['\"]?(https?://[^)'\"]+)", style):
                urls.add(u)
    return sorted(urls)


def download_images(urls):
    cache_dir = ROOT / "tools" / ".image-cache"
    cache_dir.mkdir(parents=True, exist_ok=True)
    img_dir = OUT / "assets" / "images"
    img_dir.mkdir(parents=True, exist_ok=True)
    mapping = {}
    for i, url in enumerate(urls, 1):
        path = cache_dir / f"img_{i:02d}.jpg"
        if not path.exists():
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=30) as resp:
                    path.write_bytes(resp.read())
                print(f"downloaded {i}/{len(urls)}")
            except Exception as e:
                print(f"image {i} failed: {e}")
                continue
        shutil.copy2(path, img_dir / path.name)
        mapping[url] = f"/assets/images/{path.name}"
    return mapping


def convert_node(node, mapping, image_map, in_text=False):
    if isinstance(node, (Comment, Doctype)):
        return ""
    if isinstance(node, NavigableString):
        s = str(node)
        if not s.strip():
            return ""
        return esc_text(s) if in_text else f"<text>{esc_text(s)}</text>"

    if not isinstance(node, Tag):
        return ""

    name = node.name.lower()
    if name in ("script", "style", "noscript", "link", "meta", "template", "head", "title"):
        return ""
    if in_text and name in ("img", "svg", "input", "textarea", "form", "button"):
        return ""

    # Material Symbols icon
    if name == "span" and "material-symbols-outlined" in (node.get("class") or []):
        icon = node.get_text(strip=True)
        emoji = ICONS.get(icon, "▫️")
        cls = safe_classes(node, mapping, ("mi",))
        return f'<text class="{esc_attr(cls)}">{esc_text(emoji)}</text>'

    # SVG -> base64 image
    if name == "svg":
        raw = str(node)
        if "xmlns=" not in raw[:200]:
            raw = raw.replace("<svg", '<svg xmlns="http://www.w3.org/2000/svg"', 1)
        data = base64.b64encode(raw.encode("utf-8")).decode("ascii")
        cls = safe_classes(node, mapping)
        return f'<image class="{esc_attr(cls)}" mode="aspectFit" src="data:image/svg+xml;base64,{data}" />'

    # img
    if name == "img":
        src = node.get("src", "")
        src = image_map.get(src, src)
        cls = safe_classes(node, mapping)
        mode = "aspectFill" if "object-cover" in (node.get("class") or []) else "widthFix"
        style = node.get("style")
        style_attr = f' style="{esc_attr(style)}"' if style else ""
        return f'<image class="{esc_attr(cls)}" mode="{mode}" src="{esc_attr(src)}"{style_attr} />'

    # checkbox / radio
    if name == "input" and (node.get("type") or "").lower() in ("checkbox", "radio"):
        cls = safe_classes(node, mapping, ("checkbox",))
        checked = node.has_attr("checked")
        return f'<view class="{esc_attr(cls)}">{"✓" if checked else ""}</view>'

    # input
    if name == "input":
        cls = safe_classes(node, mapping)
        typ = (node.get("type") or "text").lower()
        typ = "number" if typ in ("tel", "number") else "text"
        attrs = [f'class="{esc_attr(cls)}"', f'type="{typ}"']
        if node.get("placeholder"):
            attrs.append(f'placeholder="{esc_attr(node["placeholder"])}"')
        if node.get("value"):
            attrs.append(f'value="{esc_attr(node["value"])}"')
        if node.get("maxlength"):
            attrs.append(f'maxlength="{esc_attr(node["maxlength"])}"')
        if node.has_attr("disabled"):
            attrs.append("disabled")
        return f'<input {" ".join(attrs)} />'

    # textarea
    if name == "textarea":
        cls = safe_classes(node, mapping)
        attrs = [f'class="{esc_attr(cls)}"', 'auto-height']
        if node.get("placeholder"):
            attrs.append(f'placeholder="{esc_attr(node["placeholder"])}"')
        if node.get("maxlength"):
            attrs.append(f'maxlength="{esc_attr(node["maxlength"])}"')
        text = esc_text(node.get_text()) if node.get_text(strip=True) else ""
        return f'<textarea {" ".join(attrs)}>{text}</textarea>'

    # br / hr
    if name == "br":
        return "<text>\n</text>"
    if name == "hr":
        cls = safe_classes(node, mapping)
        return f'<view class="{esc_attr(cls)}"></view>'

    # build attrs
    attrs = []
    cls = safe_classes(node, mapping)
    if cls:
        attrs.append(f'class="{esc_attr(cls)}"')
    if node.get("id") and name != "a":
        attrs.append(f'id="{esc_attr(node["id"])}"')
    style = node.get("style")
    if style:
        # Inline background images: use package-root paths and no quote entities.
        # WeChat's renderer treats &quot;/&#x27; literally when resolving the URL.
        for url, local in image_map.items():
            style = style.replace(url, local)
        style = re.sub(r"font-variation-settings\s*:[^;]+;?", "", style)
        style = re.sub(r"url\((['\"])(.*?)\1\)", r"url(\2)", style)
        style = style.replace('"', "'")
        attrs.append(f'style="{html.escape(style, quote=False)}"')

    # choose target tag
    if name in ("a",) or name in BLOCK_TAGS:
        target = "view"
    elif name in INLINE_TAGS:
        classes = node.get("class") or []
        target = "view" if any(c.startswith(LAYOUT_HINTS) for c in classes) else "text"
    elif name == "button":
        target = "button"
        if (node.get("type") or "").lower() == "submit":
            attrs.append('form-type="submit"')
    elif name == "form":
        target = "form"
    else:
        target = "view"

    if name == "button":
        target = "button"
        if (node.get("type") or "").lower() == "submit":
            attrs.append('form-type="submit"')
    # WXML <text> cannot contain <view>/<button>/<image>; force inline descendants.
    if in_text and target in ("view", "button", "form", "image", "input"):
        target = "text"

    # children
    inner = "".join(convert_node(ch, mapping, image_map, in_text=(target == "text")) for ch in node.children)
    if target in ("image", "input"):
        return f'<{target} {" ".join(attrs)} />'
    return f'<{target} {" ".join(attrs)}>{inner}</{target}>'


def generate_css(mapping):
    base = []
    late = []
    child_rules = []
    weight_tokens = {"font-thin", "font-extralight", "font-light", "font-normal", "font-medium",
                     "font-semibold", "font-bold", "font-extrabold", "font-black"}
    for tok, safe in mapping.items():
        if tok in ("mi", "checkbox", "pb-safe", "pt-safe", "pr-capsule", "proto-card"):
            continue
        normal, late_rules = class_css(tok)
        buckets = [late] if tok in weight_tokens else [base]
        for rule in normal:
            if rule.startswith("__SPACEY__:"):
                v = rule.split(":", 1)[1]
                child_rules.append(f".{safe} > view + view, .{safe} > button + button, .{safe} > view + button, .{safe} > button + view {{margin-top:{v};}}")
            elif rule.startswith("__SPACEX__:"):
                v = rule.split(":", 1)[1]
                child_rules.append(f".{safe} > view + view, .{safe} > button + button, .{safe} > view + button, .{safe} > button + view {{margin-left:{v};}}")
            else:
                buckets[0].append(f".{safe} {{{rule};}}")
        for rule in late_rules:
            late.append(f".{safe} {{{rule};}}")
    custom = f"""
page {{ background:{PALETTE.get('background', '#f9f9ff')}; color:{PALETTE.get('on-surface', '#121c2c')}; font-family:{FONT_FAMILY}; font-size:14px; line-height:20px; }}
view, text, button, input, textarea, image, form {{ box-sizing:border-box; }}
button {{ margin:0; padding:0; border:0; border-radius:0; background:transparent; color:inherit; font:inherit; line-height:inherit; text-align:inherit; }}
button::after {{ border:none; }}
image {{ display:block; }}
input, textarea {{ font:inherit; color:inherit; background:transparent; }}
.mi {{ display:inline-block; line-height:1; font-size:1em; }}
.checkbox {{ display:flex; align-items:center; justify-content:center; border:1px solid {PALETTE.get('outline','#72787e')}; border-radius:4px; color:{PALETTE.get('primary','#00324b')}; font-size:12px; }}
.pb-safe {{ padding-bottom:env(safe-area-inset-bottom,0); }}
.pt-safe {{ padding-top:env(safe-area-inset-top,0); }}
.pr-capsule {{ padding-right:calc(env(safe-area-inset-right,0px) + 96px); }}
.proto-card {{ background:#fff; border-radius:12px; padding:14px; margin-bottom:12px; box-shadow:0 1px 2px rgba(0,0,0,.05); }}
.proto-back {{ position:fixed; right:16px; bottom:96px; z-index:9999; padding:8px 14px; border-radius:999px; background:rgba(0,50,75,.9); color:#fff; font-size:13px; line-height:18px; box-shadow:0 6px 18px rgba(0,0,0,.2); }}
"""
    css = "# Generated from the static HTML prototypes. Do not edit by hand.\n" + custom + "\n" + "\n".join(base + child_rules + late) + "\n"
    return re.sub(r"(-?\d+(?:\.\d+)?)rem", lambda m: f"{float(m.group(1)) * 16:g}px", css)


def main():
    # Keep the directory itself: WeChat DevTools may hold a handle while the project
    # is open. Files are overwritten below; stale files are harmless.
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "pages").mkdir(parents=True, exist_ok=True)
    (OUT / "utils").mkdir(parents=True, exist_ok=True)

    soups = []
    for num, _, _, _ in PAGES:
        source = (SRC / num / "code.html").read_text(encoding="utf-8", errors="ignore")
        soup = BeautifulSoup(source, "html.parser")
        body = soup.find("body")
        soups.append(body)

    mapping = build_class_map(soups)
    urls = make_image_urls(soups)
    image_map = download_images(urls)
    print(f"class tokens: {len(mapping)}, images: {len(image_map)}")

    for (num, slug, title, desc), body in zip(PAGES, soups):
        page_dir = OUT / "pages" / slug
        page_dir.mkdir(parents=True, exist_ok=True)
        wxml = convert_node(body, mapping, image_map)
        # remove empty text nodes produced by whitespace
        wxml = re.sub(r"<text></text>", "", wxml)
        # Custom navigation hides the native back button; provide a floating return control.
        wxml += '\n<view class="proto-back" bindtap="protoBack"><text>‹ 返回</text></view>\n'
        (page_dir / f"{slug}.wxml").write_text(wxml, encoding="utf-8")
        (page_dir / f"{slug}.wxss").write_text("/* Page-specific styles live in app.wxss. */\n", encoding="utf-8")
        (page_dir / f"{slug}.json").write_text(json.dumps({"navigationBarTitleText": title, "usingComponents": {}}, ensure_ascii=False, indent=2), encoding="utf-8")
        js = '''Page({
  data: {},

  protoBack() {
    if (getCurrentPages().length > 1) {
      wx.navigateBack({ delta: 1 });
    } else {
      wx.reLaunch({ url: '/pages/index/index' });
    }
  }
});
'''
        (page_dir / f"{slug}.js").write_text(js, encoding="utf-8")

    (OUT / "app.wxss").write_text(generate_css(mapping), encoding="utf-8")
    app_json = {
        "entryPagePath": "pages/index/index",
        "pages": ["pages/index/index"] + [f"pages/{slug}/{slug}" for _, slug, _, _ in PAGES],
        "window": {
            "navigationStyle": "custom",
            "backgroundColor": PALETTE.get("background", "#f9f9ff"),
            "backgroundTextStyle": "dark",
            "navigationBarTextStyle": "black"
        },
        "style": "v2",
        "sitemapLocation": "sitemap.json"
    }
    (OUT / "app.json").write_text(json.dumps(app_json, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUT / "app.js").write_text("App({\n  globalData: {\n    apiBase: 'http://127.0.0.1:8080/api/v1'\n  }\n});\n", encoding="utf-8")
    (OUT / "sitemap.json").write_text(json.dumps({"desc": "材智通原型转换", "rules": [{"action": "allow", "page": "*"}]}, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUT / "project.config.json").write_text(json.dumps({
        "description": "材智通 HTML 原型转微信小程序（视觉转换）",
        "packOptions": {"ignore": []},
        "setting": {"urlCheck": False, "es6": True, "enhance": True, "postcss": True, "minified": True, "newFeature": True},
        "compileType": "miniprogram",
        "libVersion": "3.7.12",
        "appid": "wx019bf0b6d1a50ad3",
        "projectname": "caizhitong-prototypes",
        "miniprogramRoot": "./"
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUT / "project.private.config.json").write_text(json.dumps({
        "description": "本机开发配置",
        "projectname": "caizhitong-prototypes",
        "setting": {"urlCheck": False, "compileHotReLoad": True}
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    # index page
    index_dir = OUT / "pages" / "index"
    index_dir.mkdir(parents=True, exist_ok=True)
    page_items = "\n".join(
        f'    <view class="menu-item" data-path="pages/{slug}/{slug}" bindtap="openPage">'
        f'<view class="menu-text"><text class="menu-title">{html.escape(title)}</text>'
        f'<text class="menu-desc">{html.escape(desc)}</text></view><text class="menu-arrow">›</text></view>'
        for _, slug, title, desc in PAGES
    )
    index_wxml = f'''<view class="index-page">
  <view class="index-hero">
    <text class="index-kicker">材智通 · HTML 原型转换</text>
    <text class="index-title">可导入微信开发者工具的前端</text>
    <text class="index-sub">共 {len(PAGES)} 个页面 · 仅还原静态视觉，业务交互需接入 API</text>
    <view class="index-status {{{{backendOk ? '' : 'index-status-error'}}}}">
      <text>{{{{backendStatus}}}}</text>
    </view>
  </view>
  <view class="index-list">
{page_items}
  </view>
</view>'''
    (index_dir / "index.wxml").write_text(index_wxml, encoding="utf-8")
    (index_dir / "index.wxss").write_text('''.index-page { min-height: 100vh; background: #f9f9ff; padding: 32px 16px 48px; }
.index-hero { background: #00324b; color: #fff; border-radius: 16px; padding: 20px; margin-bottom: 16px; }
.index-kicker { display: block; font-size: 11px; letter-spacing: .08em; opacity: .75; }
.index-title { display: block; font-size: 22px; font-weight: 700; line-height: 30px; margin-top: 6px; }
.index-sub { display: block; font-size: 12px; line-height: 18px; opacity: .78; margin-top: 6px; }
.index-status { margin-top: 12px; padding: 8px 10px; border-radius: 8px; background: rgba(255,255,255,.12); font-size: 12px; }
.index-status-error { background: rgba(255,120,120,.25); }
.index-list { background: #fff; border-radius: 14px; overflow: hidden; box-shadow: 0 1px 2px rgba(0,0,0,.05); }
.menu-item { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid #e7eeff; }
.menu-item:last-child { border-bottom: 0; }
.menu-text { display: flex; flex-direction: column; min-width: 0; }
.menu-title { font-size: 15px; font-weight: 600; color: #121c2c; }
.menu-desc { font-size: 11px; color: #72787e; margin-top: 2px; }
.menu-arrow { color: #00324b; font-size: 20px; margin-left: 12px; }
''', encoding="utf-8")
    (index_dir / "index.json").write_text(json.dumps({"navigationBarTitleText": "材智通原型", "usingComponents": {}}, ensure_ascii=False, indent=2), encoding="utf-8")
    (index_dir / "index.js").write_text('''const { request } = require('../../utils/request');

Page({
  data: {
    backendStatus: '正在检查后端连接…',
    backendOk: false
  },

  onLoad() {
    request('/campuses')
      .then((campuses) => {
        const names = (campuses || []).map((item) => item.name).join('、');
        this.setData({
          backendOk: true,
          backendStatus: names ? `后端连接成功：${names}` : '后端连接成功（暂无校园数据）'
        });
      })
      .catch((e) => {
        this.setData({
          backendOk: false,
          backendStatus: `后端未连接：${e.error && e.error.code ? e.error.code : 'NETWORK_ERROR'}`
        });
      });
  },

  openPage(e) {
    wx.navigateTo({ url: '/' + e.currentTarget.dataset.path });
  }
});
''', encoding="utf-8")

    # shared request wrapper
    (OUT / "utils" / "request.js").write_text('''const BASE_URL = 'http://127.0.0.1:8080/api/v1';

function request(path, options) {
  const opts = options || {};
  const header = { 'Content-Type': 'application/json' };
  if (opts.token) header.Authorization = 'Bearer ' + opts.token;
  if (opts.idempotencyKey) header['Idempotency-Key'] = opts.idempotencyKey;

  return new Promise(function (resolve, reject) {
    wx.request({
      url: BASE_URL + path,
      method: opts.method || 'GET',
      data: opts.data,
      header: header,
      timeout: opts.timeout || 10000,
      success: function (res) {
        const body = res.data || {};
        if (res.statusCode >= 200 && res.statusCode < 300 && body.success) {
          resolve(body.data);
        } else {
          reject({
            status: res.statusCode,
            error: body.error || { code: 'UNKNOWN_ERROR', message: '请求失败' },
            requestId: body.requestId
          });
        }
      },
      fail: function (err) {
        reject({ status: 0, error: { code: 'NETWORK_ERROR', message: '网络连接失败' }, raw: err });
      }
    });
  });
}

function newIdempotencyKey() {
  return 'wx-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
}

module.exports = { request, newIdempotencyKey, BASE_URL };
''', encoding="utf-8")

    # quick validation
    forbidden = re.compile(r"<script|<style|<svg|<iframe|onclick=|oninput=|onchange=|onsubmit=|href=", re.I)
    bad = []
    for path in OUT.rglob("*.wxml"):
        text = path.read_text(encoding="utf-8")
        if forbidden.search(text):
            bad.append(str(path))
    print("WXML files:", len(list(OUT.rglob("*.wxml"))))
    print("forbidden-tag files:", bad)
    print("output:", OUT)


if __name__ == "__main__":
    main()
