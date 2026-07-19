from PIL import Image
import colorsys

def shift_matcha():
    img = Image.open("src/main/resources/images/latte.jpg").convert('RGB')
    pixels = img.load()
    for i in range(img.size[0]):
        for j in range(img.size[1]):
            r, g, b = pixels[i, j]
            h, s, v = colorsys.rgb_to_hsv(r/255., g/255., b/255.)
            if s > 0.1 and v > 0.1 and 0.0 <= h <= 0.2:
                h = 0.3  # Greenish
                r, g, b = colorsys.hsv_to_rgb(h, s, v)
                pixels[i, j] = (int(r*255), int(g*255), int(b*255))
    img.save("src/main/resources/images/matcha.jpg")

def shift_tea():
    img = Image.open("src/main/resources/images/americano.jpg").convert('RGB')
    pixels = img.load()
    for i in range(img.size[0]):
        for j in range(img.size[1]):
            r, g, b = pixels[i, j]
            h, s, v = colorsys.rgb_to_hsv(r/255., g/255., b/255.)
            if s > 0.1 and v > 0.1 and 0.0 <= h <= 0.2:
                h = 0.02  # Reddish
                r, g, b = colorsys.hsv_to_rgb(h, s, v)
                pixels[i, j] = (int(r*255), int(g*255), int(b*255))
    img.save("src/main/resources/images/blacktea.jpg")

shift_matcha()
shift_tea()
