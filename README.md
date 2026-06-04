# Create Booming Lift

A NeoForge mod that sets TNT blocks to have negative mass for aeronautic builds in Create mod.

## Features

- **TNT Negative Mass**: TNT blocks have a mass of **-8.0 kpg** (kilogram-force)
- **Aeronautic Lift**: TNT-based structures float upward when compiled in Create's stable physics system
- **Creative Bomb Solutions**: Surround TNT with cobblestone to make it fall and function as traditional bombs
- **Balanced Gameplay**: High velocity collision threshold ensures only dramatic crashes trigger TNT detonation

## How It Works

### Aeronautic Mode
When TNT blocks are part of a structure compiled with Create mod's stabilization system:
1. The physics engine reads the TNT mass as -8.0 kpg
2. Negative mass causes upward acceleration on the entire structure
3. TNT-based aeronautic contraptions float and can be controlled

### Bomb Mode
Surround TNT with cobblestone blocks to counteract the negative mass:

**Mass Calculation:**
- Cobblestone mass: 2.0 kpg per block
- TNT mass: 2.0 kpg
- TNT Lift strength: 8.0 kpg
- 6 cobblestone blocks: 6 × 2.0 = 12.0 kpg
- **Fully enclosed TNT: -8 + 12.0 = +4.0 kpg (falls normally and explodes from significant fall heights)**

| Cobblestone Count | Total Mass | Behavior |
|---|---|---|
| 0 | -8.0 | Floats upward fast |
| 1 | -6.0 | Floats upward |
| 2 | -4.0 | Floats upward |
| 3 | -2.0 | Floats slowly |
| 4 | 0.0 | Neutral (hovers) |
| 5 | +2.0 | **Falls normally** |
| 6 | +4.0 | **Falls fast** |

## Building Aeronautic Structures

Use TNT as the primary lift component in floating contraptions:
- **TNT Airships**: Large buoyant vessels with TNT cores
- **Floating Platforms**: Use TNT clusters to achieve desired lift
- **Aerial Transport**: Fast-moving sky infrastructure
- **Anti-Gravity Mechanisms**: Combine with Create's kinetic systems for flight

## Building Bombs

Create traditional TNT bombs by surrounding TNT with cobblestone:

**Complete Enclosure (6 sides)**
```
  C C C
  C T C
  C C C
```
Net mass: +2.0 kpg → Falls normally

**Partial Containment (3-5 sides)**
```
  C C C
  C T .
  C C C
```
Net mass: -2.0 to +0.0 kpg → Falls slowly or hovers

## Collision-Based Detonation

TNT detonates on high-velocity collisions when:
- A TNT-containing structure impacts terrain or another structure at sufficient speed
- Impact energy = relative velocity × structure mass
- High threshold ensures only dramatic crashes trigger detonation
- Players have full control through piloting

## Building

```bash
./gradlew build
```

The compiled mod JAR will be in `build/libs/`

## Installation

1. Place the mod JAR in your `mods` folder
2. Requires:
   - NeoForge 21.1.233 or compatible
   - Sable mod (for physics system with mass configuration)

## Technical Details

### Mass System
- Base TNT Mass: 2.0 kpg
- Base TNT Lifting Power: 8.0 kpg
- Cobblestone Mass: 2.0 kpg (positive, from Sable's #sable:heavy tag)
- Integration: Through Sable's `sable:mass` configuration system

### Physics Integration
- TNT mass is read by Sable's physics engine
- Structures containing TNT experience upward acceleration based on net mass
- Effects scale with the number of TNT blocks and cobblestone placement
- Works with all Create contraption types in Sable-enabled worlds

## Gameplay Philosophy

This mod encourages:
- **Creative problem-solving** for bomb construction
- **Aeronautic contraption building** as the primary intended use
- **Strategic block placement** to achieve desired lift/weight ratios
- **Careful piloting** to avoid accidental TNT detonation
- **Experimentation** with novel TNT applications

## Future Enhancements

- Variant TNT blocks with different mass values
- Custom aeronautic blocks with tuned negative mass
- Integration with other Create addons
- Advanced lift control systems
- Visual indicators for TNT mass state

## License

MIT License

## Author

KenMunk
