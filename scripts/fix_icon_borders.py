import os
import glob
from PIL import Image

def fix_borders(images_dir="images"):
    """
    Replaces a 20px border with transparency in icons.
    Assuming 1024x1024 source images.
    """
    pattern = os.path.join(images_dir, "ic_icon*.png")
    files = glob.glob(pattern)
    
    if not files:
        print(f"No ic_icon* files found in {images_dir}")
        return

    print(f"Fixing 20px borders for {len(files)} icons...")

    for file_path in files:
        print(f"Fixing border for {file_path}...")
        
        try:
            with Image.open(file_path) as img:
                # Ensure it's RGBA
                if img.mode != 'RGBA':
                    img = img.convert('RGBA')
                
                width, height = img.size
                border = 20
                
                # Crop the interior (assuming 1024x1024, but let's be dynamic)
                interior_box = (border, border, width - border, height - border)
                interior = img.crop(interior_box)
                
                # Create a new transparent canvas of the original size
                new_img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
                
                # Paste the interior back in the center
                new_img.paste(interior, (border, border))
                
                # Save it back
                new_img.save(file_path, "PNG")
                print(f"  Fixed {file_path}")
                
        except Exception as e:
            print(f"  Error fixing {file_path}: {e}")

    print("\nBorder fix complete.")

if __name__ == "__main__":
    fix_borders()
