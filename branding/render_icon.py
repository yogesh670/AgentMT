from PIL import Image, ImageDraw
S=2048; k=S/108
def lerp(a,b,t): return tuple(int(a[i]+(b[i]-a[i])*t) for i in range(3))
c0,c1,c2=(0x0B,0x10,0x26),(0x17,0x25,0x54),(0x1D,0x4E,0xD8)
grad=Image.new("RGB",(S,S)); px=grad.load()
row=[]
for d in range(2*S):
    t=d/(2*S-1); row.append(lerp(c0,c1,t*2) if t<.5 else lerp(c1,c2,(t-.5)*2))
for y in range(S):
    for x in range(S): px[x,y]=row[x+y]
img=grad.convert("RGBA"); ov=Image.new("RGBA",(S,S),(0,0,0,0)); d=ImageDraw.Draw(ov)
d.ellipse([14*k,14*k,94*k,94*k],fill=(34,211,238,15))
def line(pts,col,w=7):
    pts=[(x*k,y*k) for x,y in pts]; d.line(pts,fill=col,width=int(w*k),joint="curve")
    r=w*k/2
    for x,y in (pts[0],pts[-1]): d.ellipse([x-r,y-r,x+r,y+r],fill=col)
line([(37,40),(51,54),(37,68)],(255,255,255,255)); r=3.5*k; d.ellipse([51*k-r,54*k-r,51*k+r,54*k+r],fill="white")
line([(57,68),(73,68)],(34,211,238,255))
def q(p0,p1,p2,n=40): return [((1-t)**2*p0[0]+2*(1-t)*t*p1[0]+t*t*p2[0],(1-t)**2*p0[1]+2*(1-t)*t*p1[1]+t*t*p2[1]) for t in [i/n for i in range(n+1)]]
star=q((71,28),(72.6,37.4),(82,39))+q((82,39),(72.6,40.6),(71,50))+q((71,50),(69.4,40.6),(60,39))+q((60,39),(69.4,37.4),(71,28))
d.polygon([(x*k,y*k) for x,y in star],fill=(251,191,36,255))
img=Image.alpha_composite(img,ov)
full=img.resize((512,512),Image.LANCZOS); full.save("play-store-icon-512.png")
def mask(sz,kind):
    m=Image.new("L",(sz*4,sz*4),0); md=ImageDraw.Draw(m)
    if kind=="round": md.ellipse([0,0,sz*4-1,sz*4-1],fill=255)
    else: md.rounded_rectangle([0,0,sz*4-1,sz*4-1],radius=int(sz*4*.22),fill=255)
    return m.resize((sz,sz),Image.LANCZOS)
for name,sz in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)]:
    import os; os.makedirs(f"../app/src/main/res/mipmap-{name}",exist_ok=True)
    base=img.resize((sz,sz),Image.LANCZOS)
    for kind,fn in [("sq","ic_launcher"),("round","ic_launcher_round")]:
        o=base.copy(); o.putalpha(mask(sz,kind)); o.save(f"../app/src/main/res/mipmap-{name}/{fn}.png")
r=img.resize((512,512),Image.LANCZOS); r.putalpha(mask(512,"sq")); r.save("icon-rounded-512.png")
