"""Predeclared four-dimensional coarse and training-only local search."""

from dataclasses import dataclass, replace
from itertools import product
from statistics import median

from model import D
from stock_model import StockConfig


LOWERS = (0, 5000, 10000, 15000, 20000, 25000, 30000)
UPPERS = (10000, 20000, 30000, 40000, 45000, 50000)
STEPS = (D("1"), D("2"), D("3"), D("4"), D("5"), D("6"), D("8"), D("10"))
AMOUNTS = (2000, 4000, 6000, 8000)
LIMIT = D("20")
SPLIT = "2025-11-07"


@dataclass(frozen=True, order=True)
class Point:
    lower: int
    upper: int
    step: object
    amount: int

    @property
    def key(self):
        number = format(self.step, "f")
        if "." in number:
            number = number.rstrip("0").rstrip(".")
        return f"L{self.lower}_U{self.upper}_S{number.replace('.', 'p')}_A{self.amount}"

    def config(self, tick=0):
        return replace(StockConfig(), lower=D(self.lower), upper=D(self.upper), ratio=1 + self.step / 100,
                       amount=D(self.amount), slippage_ticks=tick)


def valid(point):
    return (0 <= point.lower <= 30000 and 10000 <= point.upper <= 50000 and point.lower < point.upper and
            D("1") <= point.step <= D("10") and 1000 <= point.amount <= 10000)


def coarse_points():
    return sorted(Point(low, high, step, amount) for low, high, step, amount in product(LOWERS, UPPERS, STEPS, AMOUNTS)
                  if low < high)


def coarse_coordinates(point):
    return (LOWERS.index(point.lower), UPPERS.index(point.upper), STEPS.index(point.step), AMOUNTS.index(point.amount))


def coarse_distance(a, b):
    return sum(abs(x - y) for x, y in zip(coarse_coordinates(a), coarse_coordinates(b)))


def coarse_neighbors(point):
    coordinates = coarse_coordinates(point)
    axes = (LOWERS, UPPERS, STEPS, AMOUNTS)
    result = []
    for axis in range(4):
        for direction in (-1, 1):
            location = coordinates[axis] + direction
            if 0 <= location < len(axes[axis]):
                values = [point.lower, point.upper, point.step, point.amount]
                values[axis] = axes[axis][location]
                other = Point(*values)
                if other.lower < other.upper:
                    result.append(other)
    return sorted(result)


def fine_neighbors(point):
    result = []
    for field, width in (("lower", 2500), ("upper", 2500), ("step", D("0.5")), ("amount", 1000)):
        for sign in (-1, 1):
            other = replace(point, **{field: getattr(point, field) + sign * width})
            if valid(other):
                result.append(other)
    return result


def fine_centers(seeds):
    result = set()
    for seed in seeds:
        for low, high, step, amount in product((-2500, 0, 2500), (-2500, 0, 2500),
                                               (D("-0.5"), D("0"), D("0.5")), (-1000, 0, 1000)):
            point = Point(seed.lower + low, seed.upper + high, seed.step + step, seed.amount + amount)
            if valid(point):
                result.add(point)
    return sorted(result)


def pair_metrics(pair):
    if not all(summary.get("valid", True) for summary in pair):
        return D("-Infinity"), D("Infinity"), False
    worst_return = min(summary["return_pct"] for summary in pair)
    worst_drawdown = max(summary["max_drawdown_pct"] for summary in pair)
    return worst_return, worst_drawdown, worst_drawdown <= LIMIT


def rank(points, training, neighbor_function):
    scored = []
    for point in points:
        nearby = neighbor_function(point)
        if point in nearby or len(set(nearby)) != len(nearby):
            raise ValueError("Neighbors must be unique and exclude the center point.")
        if len(nearby) < 4:
            continue
        all_points = [point] + nearby
        if any(other not in training for other in all_points):
            raise ValueError("A declared physical neighbor has no training result.")
        values = [pair_metrics(training[other]) for other in all_points]
        own_return, own_dd, own_pass = values[0]
        fraction = D(sum(value[2] for value in values)) / len(values)
        if not own_pass or fraction < D("0.5"):
            continue
        scored.append({"point": point, "neighbor_count": len(nearby),
                       "neighbor_pass_fraction": fraction,
                       "neighbor_median_worst_return": median(value[0] for value in values),
                       "worst_return": own_return, "worst_drawdown": own_dd})
    scored.sort(key=lambda row: (-row["neighbor_median_worst_return"], -row["worst_return"],
                                 row["worst_drawdown"], row["point"]))
    return scored


def choose_seeds(ranked, count=3):
    result = []
    for row in ranked:
        point = row["point"]
        if all(coarse_distance(point, prior) >= 2 for prior in result):
            result.append(point)
        if len(result) == count:
            break
    if not result:
        raise ValueError("No training seed satisfies the risk limit.")
    return result
