"""Minimal SVG writer plus the geometry primitives the design system needs."""
import html

import tokens
import typeset


def esc(value):
    return html.escape(value, quote=True)


def fmt(value):
    return ("%.2f" % value).rstrip("0").rstrip(".") or "0"


class Tint:
    """A colour token used at reduced opacity.

    Written as a separate fill-opacity/stroke-opacity attribute rather than an
    eight-digit hex, because the rasteriser ignores #RRGGBBAA and would paint
    the colour fully opaque — which is how a translucent shadow turns into a
    black block.
    """

    __slots__ = ("colour", "alpha")

    def __init__(self, colour, alpha):
        self.colour = colour
        self.alpha = alpha


def rgba(hex_colour, alpha):
    return Tint(hex_colour, alpha)


def _paint(value, attribute):
    """Render a colour or Tint as SVG attributes."""
    if value is None:
        return ' %s="none"' % attribute
    if isinstance(value, Tint):
        return ' %s="%s" %s-opacity="%s"' % (attribute, value.colour, attribute,
                                             fmt(value.alpha))
    return ' %s="%s"' % (attribute, value)


_CLIP_SEQUENCE = [0]



class Canvas:
    """Accumulates SVG markup in a single coordinate space."""

    def __init__(self, width, height, title=""):
        self.width = width
        self.height = height
        self.title = title
        self.body = []
        self.defs = []

    # -- primitives ---------------------------------------------------------

    def raw(self, markup):
        self.body.append(markup)

    def rect(self, x, y, w, h, fill=None, rx=0, stroke=None, stroke_width=1):
        attrs = 'x="%s" y="%s" width="%s" height="%s"' % (fmt(x), fmt(y), fmt(w), fmt(h))
        if rx:
            attrs += ' rx="%s"' % fmt(rx)
        attrs += _paint(fill, "fill")
        if stroke is not None:
            attrs += _paint(stroke, "stroke") + ' stroke-width="%s"' % fmt(stroke_width)
        self.body.append("<rect %s/>" % attrs)

    def line(self, x1, y1, x2, y2, stroke, width=1):
        self.body.append('<line x1="%s" y1="%s" x2="%s" y2="%s"%s stroke-width="%s"/>'
                         % (fmt(x1), fmt(y1), fmt(x2), fmt(y2),
                            _paint(stroke, "stroke"), fmt(width)))

    def circle(self, cx, cy, r, fill=None, stroke=None, stroke_width=1):
        attrs = 'cx="%s" cy="%s" r="%s"%s' % (fmt(cx), fmt(cy), fmt(r), _paint(fill, "fill"))
        if stroke is not None:
            attrs += _paint(stroke, "stroke") + ' stroke-width="%s"' % fmt(stroke_width)
        self.body.append("<circle %s/>" % attrs)

    def path(self, d, fill=None, stroke=None, stroke_width=1, transform=None):
        attrs = 'd="%s"%s' % (d, _paint(fill, "fill"))
        if stroke is not None:
            attrs += (_paint(stroke, "stroke")
                      + ' stroke-width="%s" stroke-linecap="round" stroke-linejoin="round"'
                      % fmt(stroke_width))
        if transform:
            attrs += ' transform="%s"' % transform
        self.body.append("<path %s/>" % attrs)

    def group(self, name=None, transform=None, clip=None, extra=""):
        attrs = ""
        if name:
            attrs += ' data-component="%s"' % esc(name)
        if transform:
            attrs += ' transform="%s"' % transform
        if clip:
            attrs += ' clip-path="url(#%s)"' % clip
        if extra:
            attrs += " " + extra
        self.body.append("<g%s>" % attrs)
        return _GroupContext(self)

    def clip_rect(self, x, y, w, h, rx=0):
        _CLIP_SEQUENCE[0] += 1
        cid = "clip%d" % _CLIP_SEQUENCE[0]
        radius = ' rx="%s"' % fmt(rx) if rx else ""
        self.defs.append('<clipPath id="%s"><rect x="%s" y="%s" width="%s" height="%s"%s/></clipPath>'
                         % (cid, fmt(x), fmt(y), fmt(w), fmt(h), radius))
        return cid

    def embed(self, child, transform=None, clip=None, name=None):
        """Splice another canvas's markup into this one.

        clip-path and transform must live on separate groups: on one element the
        transform establishes a new user space and the clip is then resolved in
        that new space, which silently shifts the clip by the same offset.
        """
        self.defs.extend(child.defs)
        with self.group(name=name, clip=clip):
            with self.group(transform=transform):
                self.body.extend(child.body)

    # -- text ---------------------------------------------------------------

    def text(self, x, y, value, style, fill, anchor="start"):
        """Draw one already-fitted line. `y` is the baseline."""
        rendered = style.text(value)
        attrs = ('x="%s" y="%s" font-family="%s" font-size="%s" font-weight="%d"%s'
                 % (fmt(x), fmt(y), esc(style.font_family), fmt(style.size),
                    style.weight, _paint(fill, "fill")))
        if style.tracking:
            attrs += ' letter-spacing="%s"' % fmt(style.tracking)
        if anchor != "start":
            attrs += ' text-anchor="%s"' % anchor
        # data-text keeps the source string greppable even after the visual
        # hyphen has been added by the layout engine.
        self.body.append('<text %s data-text="%s">%s</text>'
                         % (attrs, esc(value), esc(rendered)))

    def paragraph(self, x, y, width, value, style, fill, anchor="start", first_width=None):
        """Wrap and draw. `y` is the top of the first line box.
        Returns the block height."""
        lines = typeset.wrap(value, style, width, first_width)
        for index, line in enumerate(lines):
            self.text(x, y + index * style.line_height + style.baseline(),
                      line, style, fill, anchor)
        return len(lines) * style.line_height

    # -- output -------------------------------------------------------------

    def to_svg(self):
        head = ('<svg xmlns="http://www.w3.org/2000/svg" width="%s" height="%s" '
                'viewBox="0 0 %s %s">\n<title>%s</title>\n'
                % (fmt(self.width), fmt(self.height), fmt(self.width),
                   fmt(self.height), esc(self.title)))
        defs = ("<defs>\n%s\n</defs>\n" % "\n".join(self.defs)) if self.defs else ""
        return head + defs + "\n".join(self.body) + "\n</svg>\n"


class _GroupContext:
    def __init__(self, canvas):
        self.canvas = canvas

    def __enter__(self):
        return self.canvas

    def __exit__(self, *exc):
        self.canvas.body.append("</g>")
        return False


# --- shapes ---------------------------------------------------------------


def cut_corner_path(x, y, w, h, radius, cut):
    """OrderableCardCutCorner: shape.medium on three corners, a straight
    diagonal cut of size.cutCorner replacing the rounding on the top right."""
    r, c = radius, cut
    return (
        "M%s,%s H%s L%s,%s V%s Q%s,%s %s,%s H%s Q%s,%s %s,%s V%s Q%s,%s %s,%s Z"
        % (fmt(x + r), fmt(y),
           fmt(x + w - c), fmt(x + w), fmt(y + c),
           fmt(y + h - r),
           fmt(x + w), fmt(y + h), fmt(x + w - r), fmt(y + h),
           fmt(x + r),
           fmt(x), fmt(y + h), fmt(x), fmt(y + h - r),
           fmt(y + r),
           fmt(x), fmt(y), fmt(x + r), fmt(y)))


def drop_shadow(canvas, draw, elevation, colour="#000000"):
    """A Material elevation shadow approximated by stacked offset copies.

    cairosvg does not implement feDropShadow, so a filter would render as
    nothing at all and the artboard would silently lose the one visual cue that
    distinguishes `dragging` and the dialog. Stacked translucent copies are an
    approximation of the blur, not of the decision: the shadow is present in
    exactly the two states DESIGN_TOKENS.md section 6.7 allows it.
    """
    layers = [(elevation * 0.9, 0.05), (elevation * 0.6, 0.06),
              (elevation * 0.35, 0.07), (elevation * 0.15, 0.08)]
    for offset, alpha in layers:
        with canvas.group(transform="translate(0,%s)" % fmt(offset)):
            draw(rgba(colour, alpha))


# --- icons -----------------------------------------------------------------


def chevron(canvas, cx, cy, size, colour, direction, stroke=tokens.ICON_STROKE_DEFAULT):
    """A stroked chevron on a size x size icon box."""
    arm = size * 0.25
    if direction in ("up", "down"):
        dy = -arm if direction == "up" else arm
        d = "M%s,%s L%s,%s L%s,%s" % (fmt(cx - arm), fmt(cy - dy / 2),
                                      fmt(cx), fmt(cy + dy / 2),
                                      fmt(cx + arm), fmt(cy - dy / 2))
    else:
        dx = -arm if direction == "left" else arm
        d = "M%s,%s L%s,%s L%s,%s" % (fmt(cx - dx / 2), fmt(cy - arm),
                                      fmt(cx + dx / 2), fmt(cy),
                                      fmt(cx - dx / 2), fmt(cy + arm))
    canvas.path(d, stroke=colour, stroke_width=stroke)


def archive_icon(canvas, cx, cy, colour):
    """Header icon: archive. 24 dp glyph inside a 48 dp target."""
    s = tokens.ICON_DEFAULT
    w = tokens.ICON_STROKE_DEFAULT
    canvas.rect(cx - s * 0.40, cy - s * 0.35, s * 0.80, s * 0.18,
                rx=1, stroke=colour, stroke_width=w)
    canvas.rect(cx - s * 0.36, cy - s * 0.17, s * 0.72, s * 0.52,
                rx=1.5, stroke=colour, stroke_width=w)
    canvas.line(cx - s * 0.12, cy + s * 0.06, cx + s * 0.12, cy + s * 0.06, colour, w)


def settings_icon(canvas, cx, cy, colour):
    """Header icon: settings (sliders — no rotating gear motif)."""
    s = tokens.ICON_DEFAULT
    w = tokens.ICON_STROKE_DEFAULT
    for row, knob in ((-0.28, 0.18), (0.02, -0.14), (0.32, 0.26)):
        y = cy + s * row
        canvas.line(cx - s * 0.38, y, cx + s * 0.38, y, colour, w)
        canvas.circle(cx + s * knob, y, s * 0.11, fill="none",
                      stroke=colour, stroke_width=w)


def drag_handle(canvas, cx, cy, colour):
    """DragHandle: 2 x 3 dot grid, icon.size.dragHandleGlyph (16 x 24 dp)."""
    gw, gh = tokens.DRAG_HANDLE_GLYPH
    radius = 1.6
    for col in (-1, 1):
        for row in (-1, 0, 1):
            canvas.circle(cx + col * gw * 0.25, cy + row * gh * 0.30, radius, fill=colour)


def category_icon(canvas, x, y, size, colour, category):
    """A Material Symbols Outlined pictogram scaled into a `size` box."""
    from category_icons import CATEGORY_ICONS
    icon = CATEGORY_ICONS[category]
    scale = size / 24.0
    canvas.path(icon["path"], fill=colour,
                transform="translate(%s,%s) scale(%s)" % (fmt(x), fmt(y), fmt(scale)))
